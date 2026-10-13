package dev.orwell.insta.graph;

import dev.orwell.env.Env;
import dev.orwell.insta.InstaEnvs;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.ConnectionsPage;
import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.insta.instagram.InstagramProfile;
import dev.orwell.insta.instagram.InstagramService;
import dev.orwell.insta.instagram.ProfileNotFoundException;
import dev.orwell.logging.Logger;

import java.io.PrintStream;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code insta sync <username>} — walk an account's followers (and, if asked, the accounts it
 * follows) and record the result in Postgres. {@code insta sync-all} does the same for every account
 * the graph already holds.
 *
 * <p>The order matters. The profile lookup happens first because it is one cheap dataset item and
 * it answers the question that decides everything after it: <b>is this account private?</b> A
 * private account's lists come back empty, which is a flawless impression of every follower
 * leaving at once. Walking them is pointless and diffing them is dangerous, so the walks are
 * skipped outright.
 *
 * <p>Three things can skip a walk after that lookup, and only the lookup is paid for when they
 * do: a {@link ScrapeBlockStore} entry for that direction, a list size over
 * {@code INSTA_SKIP_ABOVE_FOLLOWERS}, and the private check above. The block is asked first, so a
 * deliberate decision is reported as one instead of being attributed to whichever automatic rule
 * happened to fire too.
 *
 * <p>One account's sync is one transaction. A sync that dies halfway through leaves that account's
 * graph exactly as it was rather than half-refreshed — which matters because a partly-refreshed
 * graph looks, to the next run, like a set of unfollows. {@code sync-all} commits per account, so
 * a run that stops on account 300 keeps the 299 before it.
 *
 * <p><b>{@code sync-all} is not a recursive crawl, but repeating it is one.</b> It visits the
 * accounts that were in the table when it started; the accounts their walks discover are left for
 * the next run. So each run reaches one hop further out than the last, and its cost grows with
 * the table — {@code --max-accounts} and the least-recently-synced ordering in
 * {@link KnownAccounts} are what keep that bounded.
 */
public final class SyncCommand {
    /**
     * Failures that would fail every remaining account the same way, so {@code sync-all} stops
     * instead of paying for a profile lookup per account just to be refused each time.
     */
    private static final Set<ApifyException.Kind> STOPS_THE_RUN = EnumSet.of(
            ApifyException.Kind.OUT_OF_CREDIT,
            ApifyException.Kind.TOKEN_REJECTED,
            ApifyException.Kind.RATE_LIMITED);

    private final Env env;
    private final InstagramService instagram;
    private final Logger logger;

    public SyncCommand(Env env, InstagramService instagram, Logger logger) {
        this.env = Objects.requireNonNull(env, "env");
        this.instagram = Objects.requireNonNull(instagram, "instagram");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** @return the process exit code. */
    public int run(
            String username, List<ConnectionType> directions, Integer limit,
            PrintStream out, PrintStream err) throws Exception {
        if (!databaseConfigured(err)) {
            return 2;
        }
        try (Connection connection = connect();
             PictureStore pictures = pictureStore()) {
            GraphSchema.apply(connection);
            connection.setAutoCommit(false);
            return inTransaction(connection,
                    () -> sync(connection, pictures, username, directions, limit, out, err));
        }
    }

    /**
     * Syncs every account already in the graph, one transaction each.
     *
     * <p>A failure that is about one account — it no longer exists, its actor run failed or timed
     * out — is reported and skipped. One that is about the Apify account itself
     * ({@link #STOPS_THE_RUN}) ends the run and propagates, so the exit code says why.
     *
     * @param maxAccounts at most this many accounts, or all of them when null
     * @return the process exit code: 4 if any account failed, 0 otherwise
     */
    public int runAll(
            List<ConnectionType> directions, Integer limit, Integer maxAccounts,
            PrintStream out, PrintStream err) throws Exception {
        if (!databaseConfigured(err)) {
            return 2;
        }
        try (Connection connection = connect();
             PictureStore pictures = pictureStore()) {
            GraphSchema.apply(connection);
            KnownAccounts known = new KnownAccounts(connection);
            // Read once, up front: the walks below add accounts, and visiting those too would turn
            // one run into an unbounded crawl.
            List<KnownAccounts.Entry> accounts = known.dueForSync(maxAccounts);
            int withoutHandle = known.withoutHandle();
            connection.setAutoCommit(false);

            err.printf("sync-all: %d accounts, %s%n", accounts.size(), describe(directions));
            if (withoutHandle > 0) {
                err.printf("%d accounts have no recorded handle and were left out%n", withoutHandle);
            }

            int synced = 0;
            List<String> failed = new ArrayList<>();
            for (int index = 0; index < accounts.size(); index++) {
                KnownAccounts.Entry account = accounts.get(index);
                err.printf("[%d/%d] %s%n", index + 1, accounts.size(), account.username());
                try {
                    int code = inTransaction(connection, () -> sync(
                            connection, pictures, account.username(), directions, limit, out, err));
                    if (code == 0) {
                        synced++;
                    } else {
                        failed.add(account.username());
                    }
                } catch (ProfileNotFoundException | ApifyException failure) {
                    if (failure instanceof ApifyException apify
                            && STOPS_THE_RUN.contains(apify.kind())) {
                        out.printf("sync-all stopped after %d of %d accounts (%d failed): %s%n",
                                synced, accounts.size(), failed.size(), failure.getMessage());
                        throw failure;
                    }
                    failed.add(account.username());
                    out.printf("%s: skipped — %s%n", account.username(), failure.getMessage());
                    logger.warn("Skipped an account in sync-all.", Map.of(
                            "accountId", account.accountId(),
                            "username", account.username(),
                            "error", String.valueOf(failure.getMessage())));
                }
            }

            out.printf("sync-all: %d of %d accounts synced, %d failed%n",
                    synced, accounts.size(), failed.size());
            if (!failed.isEmpty()) {
                out.println("failed: " + String.join(", ", failed));
            }
            return failed.isEmpty() ? 0 : 4;
        }
    }

    private int sync(
            Connection connection, PictureStore pictures, String username,
            List<ConnectionType> directions, Integer limit,
            PrintStream out, PrintStream err) throws Exception {

        AccountWriter accountWriter =
                new AccountWriter(connection, new HttpPictureSource(logger), pictures, logger);

        Instant profileFetched = Instant.now();
        InstagramProfile profile = instagram.profile(username);
        accountWriter.record(profile, profileFetched);

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
                .record(profile.id(), profile.latestPosts(), profileFetched);
        out.printf("%s: profile and %d posts recorded%n", profile.username(), posts);

        for (ConnectionType direction : directions) {
            syncDirection(connection, accountWriter, profile, username, direction, limit, out, err);
        }
        return 0;
    }

    private void syncDirection(
            Connection connection, AccountWriter accountWriter, InstagramProfile profile,
            String username, ConnectionType direction, Integer limit,
            PrintStream out, PrintStream err) throws Exception {

        FollowWriter followWriter = new FollowWriter(
                connection, accountWriter,
                env.get(InstaEnvs.INSTA_MAX_RETIRE_PERCENT) / 100.0, logger);
        ScrapeRunWriter runWriter = new ScrapeRunWriter(connection, logger);
        String list = listName(direction);
        // The watermark, so it is read before anything about this direction is fetched.
        Instant walkStarted = Instant.now();

        // Asked before the two automatic skips below, because this one is a decision somebody made
        // on purpose and the message should say so rather than blaming a list size.
        Optional<ScrapeBlockStore.Block> block =
                new ScrapeBlockStore(connection).find(profile.id(), direction);
        if (block.isPresent()) {
            String reason = block.get().reason();
            runWriter.recordSkipped(
                    profile.id(), direction, walkStarted, Instant.now(),
                    reason == null || reason.isBlank() ? "blocked" : "blocked: " + reason);
            out.printf("%s: %s walk is blocked%s%n",
                    profile.username(), singular(direction),
                    reason == null || reason.isBlank() ? "" : " (" + reason + ")");
            return;
        }

        // The count for the direction being walked. Checked before the walk, so an oversized list
        // costs one dataset item rather than the list itself.
        int ceiling = env.get(InstaEnvs.INSTA_SKIP_ABOVE_FOLLOWERS);
        Long size = direction == ConnectionType.FOLLOWERS
                ? profile.followersCount()
                : profile.followingCount();
        if (ceiling > 0 && size != null && size > ceiling) {
            runWriter.recordSkipped(
                    profile.id(), direction, walkStarted, Instant.now(),
                    "above the ceiling: " + size + " " + list + " > " + ceiling);
            out.printf("%s: %,d %s is above the %,d ceiling — %s list not walked.%n",
                    profile.username(), size, list, ceiling, singular(direction));
            logger.info("Skipped a walk over the ceiling.", Map.of(
                    "username", profile.username(), "direction", direction.name(),
                    "size", size, "ceiling", ceiling));
            return;
        }
        if (Boolean.TRUE.equals(profile.isPrivate())) {
            // Recording the profile was still worth doing; walking a private account is not.
            runWriter.recordSkipped(
                    profile.id(), direction, walkStarted, Instant.now(), "the account is private");
            out.printf("%s is private: %s list not walked.%n",
                    profile.username(), singular(direction));
            return;
        }

        Walk walk = walkAll(username, direction, limit, err);
        Instant observedAt = Instant.now();
        FollowDiff diff = followWriter.record(
                profile.id(), direction, walk.accounts(), walkStarted, observedAt, walk.complete());
        // After the follow writer, never before: the snapshot's rows point at `account`, and it is
        // the follow writer that puts a walk's accounts there.
        runWriter.record(
                profile.id(), direction, walk.accounts(),
                walkStarted, observedAt, walk.complete(), diff);

        out.printf("%s: %d %s seen, %d new, %d unfollowed%n",
                profile.username(), diff.seen(), list, diff.added(), diff.retired());
        if (!diff.retirementRan()) {
            // Saying nothing here would let a silently-skipped diff look like "no unfollows".
            out.printf("unfollows not computed: %s%n", diff.retirementSkipped());
        }
        if (diff.skippedNoId() > 0) {
            out.printf("%d rows skipped for having no id%n", diff.skippedNoId());
        }
    }

    /** Walks every page. Completeness is the actor running out of cursor, nothing else. */
    private Walk walkAll(
            String username, ConnectionType direction, Integer limit, PrintStream err) {
        List<InstagramAccount> collected = new ArrayList<>();
        String cursor = null;
        int page = 0;
        while (true) {
            ConnectionsPage result = instagram.connections(username, direction, limit, cursor);
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
                        "username", username, "direction", direction.name(), "pages", page));
                return new Walk(collected, false);
            }
            cursor = next;
            err.printf("... %d %s so far, fetching page %d%n",
                    collected.size(), listName(direction), page + 1);
        }
    }

    private boolean databaseConfigured(PrintStream err) {
        String url = env.get(InstaEnvs.INSTA_DATABASE_URL);
        if (url == null || url.isBlank()) {
            err.println("sync needs INSTA_DATABASE_URL (and usually INSTA_DATABASE_USERNAME "
                    + "/ INSTA_DATABASE_PASSWORD).");
            return false;
        }
        return true;
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(
                env.get(InstaEnvs.INSTA_DATABASE_URL),
                env.get(InstaEnvs.INSTA_DATABASE_USERNAME),
                env.get(InstaEnvs.INSTA_DATABASE_PASSWORD));
    }

    private static int inTransaction(Connection connection, Callable<Integer> work)
            throws Exception {
        try {
            int code = work.call();
            connection.commit();
            return code;
        } catch (Exception failure) {
            connection.rollback();
            throw failure;
        }
    }

    /** "followers" / "following" — the list as a plural noun. */
    private static String listName(ConnectionType direction) {
        return direction.name().toLowerCase(Locale.ROOT);
    }

    /** "follower" / "following" — the list as an adjective, as in "follower walk". */
    private static String singular(ConnectionType direction) {
        return direction == ConnectionType.FOLLOWERS ? "follower" : "following";
    }

    private static String describe(List<ConnectionType> directions) {
        return directions.stream().map(SyncCommand::listName)
                .reduce((a, b) -> a + " and " + b).orElse("no lists");
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
