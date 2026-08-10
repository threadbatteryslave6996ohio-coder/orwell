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

/**
 * {@code insta sync <username>} — walk an account's followers and record the result in Postgres.
 *
 * <p>The order matters. The profile lookup happens first because it is one cheap dataset item and
 * it answers the question that decides everything after it: <b>is this account private?</b> A
 * private account's follower list comes back empty, which is a flawless impression of every
 * follower leaving at once. Walking it is pointless and diffing it is dangerous, so the walk is
 * skipped outright.
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
                new AccountWriter(connection, new HttpPictureSource(), pictures, logger);
        FollowWriter followWriter = new FollowWriter(
                connection, accountWriter,
                env.get(InstaEnvs.INSTA_MAX_RETIRE_PERCENT) / 100.0, logger);

        Instant walkStarted = Instant.now();
        InstagramProfile profile = instagram.profile(username);
        accountWriter.record(profile, walkStarted);

        if (profile.id() == null || profile.id().isBlank()) {
            err.println("That account has no Instagram id, so it cannot be recorded.");
            return 4;
        }
        if (Boolean.TRUE.equals(profile.isPrivate())) {
            // Recording the profile was still worth doing; walking a private account is not.
            out.printf("%s is private: profile recorded, follower list not walked.%n",
                    profile.username());
            return 0;
        }

        Walk walk = walkAll(username, limit, err);
        FollowDiff diff = followWriter.record(
                profile.id(), ConnectionType.FOLLOWERS, walk.accounts(),
                walkStarted, Instant.now(), walk.complete());

        out.printf("%s: %d followers seen, %d new, %d unfollowed%n",
                profile.username(), diff.seen(), diff.added(), diff.retired());
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
                // The only exit that means "we saw the whole list". Every other way out of this
                // loop either throws — rolling the transaction back — or is marked incomplete.
                return new Walk(collected, true);
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
