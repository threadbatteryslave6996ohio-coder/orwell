package dev.orwell.insta.graph;

import dev.orwell.env.Env;
import dev.orwell.insta.InstaEnvs;
import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.ConnectionsPage;
import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.insta.instagram.InstagramProfile;
import dev.orwell.insta.instagram.InstagramService;
import dev.orwell.logging.Logger;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code insta sync <username>} — walk an account's followers and record the result in Postgres.
 *
 * <p>The order matters. The profile lookup happens first because it is one cheap dataset item and
 * it answers the question that decides everything after it: <b>is this account private?</b> A
 * private account's follower list comes back empty, which is a flawless impression of every
 * follower leaving at once. Walking it is pointless and diffing it is dangerous, so the walk is
 * skipped outright.
 *
 * <p>Three things can skip the walk after that lookup, and only the lookup is paid for when they
 * do: a {@link ScrapeBlockStore} entry, a follower count over {@code INSTA_SKIP_ABOVE_FOLLOWERS},
 * and the private check above. The block is asked first, so a deliberate decision is reported as
 * one instead of being attributed to whichever automatic rule happened to fire too.
 *
 * <p>The whole write is one transaction. A sync that dies halfway through leaves the graph exactly
 * as it was rather than half-refreshed — which matters because a partly-refreshed graph looks, to
 * the next run, like a set of unfollows.
 */
public final class SyncCommand {
    private final Env env;
    private final InstagramService instagram;
    private final Logger logger;

    public SyncCommand(Env env, InstagramService instagram, Logger logger) {
        this.env = Objects.requireNonNull(env, "env");
        this.instagram = Objects.requireNonNull(instagram, "instagram");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return the process exit code. */
    public int run(String username, Integer limit, PrintStream out, PrintStream err)
            throws Exception {
        String url = env.get(InstaEnvs.INSTA_DATABASE_URL);
        if (url == null || url.isBlank()) {
            err.println("sync needs INSTA_DATABASE_URL (and usually INSTA_DATABASE_USERNAME "
                    + "/ INSTA_DATABASE_PASSWORD).");
            return 2;
        }

        try (Connection connection = DriverManager.getConnection(
                url,
                env.get(InstaEnvs.INSTA_DATABASE_USERNAME),
                env.get(InstaEnvs.INSTA_DATABASE_PASSWORD));
             PictureStore pictures = pictureStore()) {

            GraphSchema.apply(connection);
            connection.setAutoCommit(false);
            try {
                int code = sync(connection, pictures, username, limit, out, err);
                connection.commit();
                return code;
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private int sync(
            Connection connection, PictureStore pictures, String username, Integer limit,
            PrintStream out, PrintStream err) throws Exception {

        AccountWriter accountWriter =
                new AccountWriter(connection, new HttpPictureSource(logger), pictures, logger);
        FollowWriter followWriter = new FollowWriter(
                connection, accountWriter,
                env.get(InstaEnvs.INSTA_MAX_RETIRE_PERCENT) / 100.0, logger);
        ScrapeRunWriter runWriter = new ScrapeRunWriter(connection, logger);

        Instant walkStarted = Instant.now();
        InstagramProfile profile = instagram.profile(username);
        accountWriter.record(profile, walkStarted);

        if (profile.id() == null || profile.id().isBlank()) {
            err.println("That account has no Instagram id, so it cannot be recorded.");
            return 4;
        }
        // After the id check, not before: post rows carry a foreign key to the account, so writing
        // them for an account that was never inserted would fail the whole transaction.
        //
        // The profile actor bundles up to twelve recent posts into the item we already paid for,
        // so recording them costs nothing beyond the lookup that just happened.
        int posts = new PostWriter(connection, new HttpPictureSource(logger), pictures, logger)
                .record(profile.id(), profile.latestPosts(), walkStarted);
        // Asked before the two automatic skips below, because this one is a decision somebody made
        // on purpose and the message should say so rather than blaming a follower count.
        Optional<ScrapeBlockStore.Block> block =
                new ScrapeBlockStore(connection).find(profile.id(), ConnectionType.FOLLOWERS);
        if (block.isPresent()) {
            String reason = block.get().reason();
            runWriter.recordSkipped(
                    profile.id(), ConnectionType.FOLLOWERS, walkStarted, Instant.now(),
                    reason == null || reason.isBlank() ? "blocked" : "blocked: " + reason);
            out.printf("%s: follower walk is blocked%s — profile and %d posts recorded.%n",
                    profile.username(),
                    reason == null || reason.isBlank() ? "" : " (" + reason + ")",
                    posts);
            return 0;
        }

        // The count for the direction being walked — followers here; a following walk would read
        // followingCount. Checked before the walk, so an oversized account costs one dataset item
        // rather than a list.
        int ceiling = env.get(InstaEnvs.INSTA_SKIP_ABOVE_FOLLOWERS);
        Long followers = profile.followersCount();
        if (ceiling > 0 && followers != null && followers > ceiling) {
            runWriter.recordSkipped(
                    profile.id(), ConnectionType.FOLLOWERS, walkStarted, Instant.now(),
                    "above the follower ceiling: " + followers + " > " + ceiling);
            out.printf("%s: %,d followers is above the %,d ceiling — profile and %d posts "
                            + "recorded, follower list not walked.%n",
                    profile.username(), followers, ceiling, posts);
            logger.info("Skipped a walk over the follower ceiling.", Map.of(
                    "username", profile.username(), "followers", followers, "ceiling", ceiling));
            return 0;
        }
        if (Boolean.TRUE.equals(profile.isPrivate())) {
            // Recording the profile was still worth doing; walking a private account is not.
            runWriter.recordSkipped(
                    profile.id(), ConnectionType.FOLLOWERS, walkStarted, Instant.now(),
                    "the account is private");
            out.printf("%s is private: profile and %d posts recorded, follower list not walked.%n",
                    profile.username(), posts);
            return 0;
        }

        Walk walk = walkAll(username, limit, err);
        Instant observedAt = Instant.now();
        FollowDiff diff = followWriter.record(
                profile.id(), ConnectionType.FOLLOWERS, walk.accounts(),
                walkStarted, observedAt, walk.complete());
        // After the follow writer, never before: the snapshot's rows point at `account`, and it is
        // the follow writer that puts a walk's accounts there.
        runWriter.record(
                profile.id(), ConnectionType.FOLLOWERS, walk.accounts(),
                walkStarted, observedAt, walk.complete(), diff);

        out.printf("%s: %d followers seen, %d new, %d unfollowed, %d posts%n",
                profile.username(), diff.seen(), diff.added(), diff.retired(), posts);
        if (!diff.retirementRan()) {
            // Saying nothing here would let a silently-skipped diff look like "no unfollows".
            out.printf("unfollows not computed: %s%n", diff.retirementSkipped());
        }
        if (diff.skippedNoId() > 0) {
            out.printf("%d rows skipped for having no id%n", diff.skippedNoId());
        }
        return 0;
    }

    /** Walks every page. Completeness is the actor running out of cursor, nothing else. */
    private Walk walkAll(String username, Integer limit, PrintStream err) {
        List<InstagramAccount> collected = new ArrayList<>();
        String cursor = null;
        int page = 0;
        while (true) {
            ConnectionsPage result = instagram.connections(
                    username, ConnectionType.FOLLOWERS, limit, cursor);
            collected.addAll(result.accounts());
            page++;
            String next = result.nextCursor();
            if (next == null) {
                // endOfList, not "no cursor": an actor that cannot paginate always returns none,
                // and a full page from one of those proves nothing about what it did not return.
                return new Walk(collected, result.endOfList());
            }
            if (next.equals(cursor)) {
                // A cursor that does not advance would page forever, and every round is billed.
                logger.warn("Stopped a walk whose cursor stopped advancing.", Map.of(
                        "username", username, "pages", page));
                return new Walk(collected, false);
            }
            cursor = next;
            err.printf("... %d followers so far, fetching page %d%n", collected.size(), page + 1);
        }
    }

    private PictureStore pictureStore() {
        String choice = env.get(InstaEnvs.INSTA_PICTURE_STORE).trim().toLowerCase(Locale.ROOT);
        return switch (choice) {
            case "filesystem" -> {
                String directory = env.get(InstaEnvs.INSTA_PICTURE_DIR);
                if (directory == null || directory.isBlank()) {
                    throw new IllegalArgumentException(
                            "INSTA_PICTURE_STORE=filesystem needs INSTA_PICTURE_DIR.");
                }
                logger.info("Storing profile pictures on disk.", Map.of("directory", directory));
                yield new FilesystemPictureStore(Path.of(directory));
            }
            case "http" -> {
                String bucket = env.get(InstaEnvs.INSTA_BUCKET_URL);
                if (bucket == null || bucket.isBlank()) {
                    throw new IllegalArgumentException(
                            "INSTA_PICTURE_STORE=http needs INSTA_BUCKET_URL.");
                }
                logger.info("Storing profile pictures in a bucket.", Map.of("url", bucket));
                yield new HttpPictureStore(bucket, env.get(InstaEnvs.INSTA_BUCKET_TOKEN), logger);
            }
            case "none" -> {
                logger.info("Profile picture storage is off; no images will be downloaded.");
                yield new DisabledPictureStore();
            }
            default -> throw new IllegalArgumentException(
                    "INSTA_PICTURE_STORE must be none, filesystem or http, got: " + choice);
        };
    }

    private record Walk(List<InstagramAccount> accounts, boolean complete) {
    }

}
