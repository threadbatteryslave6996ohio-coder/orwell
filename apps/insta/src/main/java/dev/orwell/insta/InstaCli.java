package dev.orwell.insta;

import dev.orwell.env.Env;
import dev.orwell.insta.apify.ApifyClient;
import dev.orwell.insta.apify.ApifyException;
import dev.orwell.insta.cache.DisabledScrapeCache;
import dev.orwell.insta.cache.RedisScrapeCache;
import dev.orwell.insta.cache.ScrapeCache;
import dev.orwell.insta.instagram.ConnectionType;
import dev.orwell.insta.instagram.ConnectionsPage;
import dev.orwell.insta.instagram.InstagramAccount;
import dev.orwell.insta.instagram.InstagramProfile;
import dev.orwell.insta.instagram.InstagramService;
import dev.orwell.insta.instagram.ProfileNotFoundException;
import dev.orwell.logging.Logger;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The program. Looks up one public Instagram account and prints the answer.
 *
 * <pre>
 * insta profile   &lt;username&gt;
 * insta followers &lt;username&gt; [--limit N] [--all] [--cursor C] [--json]
 * insta following &lt;username&gt; [--limit N] [--all] [--cursor C] [--json]
 * </pre>
 *
 * <p>Results go to stdout and nothing else does, so {@code --json} composes with {@code jq}.
 * Progress and failures go to stderr. The exit code distinguishes the failures worth reacting to
 * differently — an exhausted Apify balance is not a missing account is not a broken actor — which
 * is the same distinction {@link ApifyException.Kind} draws inside the program.
 */
public final class InstaCli {
    static final int OK = 0;
    static final int UNEXPECTED = 1;
    static final int BAD_USAGE = 2;
    static final int NOT_FOUND = 3;
    static final int UPSTREAM_FAILED = 4;
    static final int OUT_OF_CREDIT = 5;
    static final int TIMED_OUT = 6;

    private static final String USAGE = """
            usage: insta <command> [options]

            commands:
              profile   <username>            follower and following counts
              followers <username>            the accounts following them
              following <username>            the accounts they follow

            options (list commands only):
              --limit N     accounts per lookup (default INSTA_DEFAULT_LIMIT, max INSTA_MAX_LIMIT)
              --all         keep fetching pages until the list is exhausted
              --cursor C    resume from a previous run's nextCursor
              --json        print raw JSON instead of a readable list

            APIFY_TOKEN must be set, in the environment or a .env file.
            """;

    private InstaCli() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Split out from {@link #main} so it is testable: it returns the exit code rather than calling
     * {@link System#exit}, and writes to the streams it is given.
     */
    static int run(String[] args, PrintStream out, PrintStream err) {
        Arguments arguments;
        try {
            arguments = Arguments.parse(args);
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            err.println();
            err.print(USAGE);
            return BAD_USAGE;
        }
        if (arguments.help()) {
            out.print(USAGE);
            return OK;
        }

        Env env;
        try {
            env = InstaEnvs.load();
        } catch (Exception exception) {
            // A missing APIFY_TOKEN lands here, before anything can be spent.
            err.println("Configuration error: " + exception.getMessage());
            return BAD_USAGE;
        }

        // The logger is closed alongside the cache: its Loki sink batches on a daemon thread, so
        // a program this short-lived would otherwise exit with records still queued.
        try (InstaLogger logger = InstaLogger.from(env, err);
             ScrapeCache cache = cacheFrom(env, logger)) {
            InstagramService instagram = serviceFrom(env, cache, logger);
            return switch (arguments.command()) {
                case PROFILE -> printProfile(instagram, arguments, out);
                case FOLLOWERS -> printConnections(
                        instagram, arguments, ConnectionType.FOLLOWERS, out, err);
                case FOLLOWING -> printConnections(
                        instagram, arguments, ConnectionType.FOLLOWING, out, err);
            };
        } catch (IllegalArgumentException exception) {
            err.println(exception.getMessage());
            return BAD_USAGE;
        } catch (ProfileNotFoundException exception) {
            err.println(exception.getMessage());
            return NOT_FOUND;
        } catch (ApifyException exception) {
            err.println(exception.getMessage());
            return switch (exception.kind()) {
                case OUT_OF_CREDIT -> OUT_OF_CREDIT;
                case TIMED_OUT -> TIMED_OUT;
                default -> UPSTREAM_FAILED;
            };
        } catch (Exception exception) {
            err.println("Unexpected failure: " + exception);
            return UNEXPECTED;
        }
    }

    private static int printProfile(
            InstagramService instagram, Arguments arguments, PrintStream out) throws Exception {
        InstagramProfile profile = instagram.profile(arguments.username());
        if (arguments.json()) {
            out.println(InstaJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(profile));
            return OK;
        }
        out.printf("%-12s %s%n", "username", profile.username());
        out.printf("%-12s %s%n", "name", text(profile.fullName()));
        out.printf("%-12s %s%n", "followers", count(profile.followersCount()));
        out.printf("%-12s %s%n", "following", count(profile.followingCount()));
        out.printf("%-12s %s%n", "posts", count(profile.postsCount()));
        out.printf("%-12s %s%n", "private", text(profile.isPrivate()));
        out.printf("%-12s %s%n", "verified", text(profile.isVerified()));
        return OK;
    }

    /**
     * With {@code --all}, walks the cursor to the end of the list. That loop is the reason this is
     * worth being a program rather than a single call: every page is a separate paid actor run, and
     * doing it by hand means keeping track of an opaque token between invocations.
     */
    private static int printConnections(
            InstagramService instagram, Arguments arguments, ConnectionType type,
            PrintStream out, PrintStream err) throws Exception {
        List<InstagramAccount> collected = new ArrayList<>();
        String cursor = arguments.cursor();
        int pages = 0;
        String nextCursor;
        do {
            ConnectionsPage page = instagram.connections(
                    arguments.username(), type, arguments.limit(), cursor);
            collected.addAll(page.accounts());
            nextCursor = page.nextCursor();
            cursor = nextCursor;
            pages++;
            if (arguments.all() && nextCursor != null) {
                err.printf("... %d accounts so far, fetching page %d%n", collected.size(), pages + 1);
            }
        } while (arguments.all() && nextCursor != null);

        if (arguments.json()) {
            out.println(InstaJson.mapper().writerWithDefaultPrettyPrinter()
                    .writeValueAsString(new ConnectionsPage(collected, nextCursor)));
            return OK;
        }
        for (InstagramAccount account : collected) {
            out.println(account.fullName() == null || account.fullName().isBlank()
                    ? account.username()
                    : account.username() + "\t" + account.fullName());
        }
        // The count line goes to stderr so piping the usernames stays clean.
        err.printf("%d %s of %s%n", collected.size(),
                type.name().toLowerCase(Locale.ROOT), arguments.username());
        if (nextCursor != null) {
            err.println("More remain. Resume with --cursor " + nextCursor);
        }
        return OK;
    }

    private static InstagramService serviceFrom(Env env, ScrapeCache cache, Logger logger) {
        ApifyClient apify = new ApifyClient(
                env.get(InstaEnvs.APIFY_BASE_URL),
                env.get(InstaEnvs.APIFY_TOKEN),
                env.get(InstaEnvs.APIFY_RUN_TIMEOUT_SECONDS),
                logger);
        return new InstagramService(
                apify, cache,
                env.get(InstaEnvs.APIFY_PROFILE_ACTOR),
                env.get(InstaEnvs.APIFY_CONNECTIONS_ACTOR),
                env.get(InstaEnvs.INSTA_DEFAULT_LIMIT),
                env.get(InstaEnvs.INSTA_MAX_LIMIT),
                logger);
    }

    private static ScrapeCache cacheFrom(Env env, Logger logger) {
        if (!env.get(InstaEnvs.INSTA_CACHE_ENABLED)) {
            logger.warn("Caching is off; this lookup will run a paid Apify actor.");
            return new DisabledScrapeCache();
        }
        return new RedisScrapeCache(
                env.get(InstaEnvs.REDIS_HOST),
                env.get(InstaEnvs.REDIS_PORT),
                Duration.ofHours(Math.max(env.get(InstaEnvs.INSTA_CACHE_TTL_HOURS), 1)),
                logger);
    }

    private static String count(Long value) {
        return value == null ? "unknown" : String.format("%,d", value);
    }

    private static String text(Object value) {
        return value == null ? "unknown" : String.valueOf(value);
    }

    enum Command {
        PROFILE, FOLLOWERS, FOLLOWING
    }

    /**
     * One invocation's arguments. Hand-parsed rather than pulling in a CLI library: three commands
     * and four flags do not justify a dependency.
     */
    record Arguments(
            Command command, String username, Integer limit, boolean all, String cursor,
            boolean json, boolean help) {

        static Arguments parse(String[] args) {
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                return new Arguments(Command.PROFILE, null, null, false, null, false, true);
            }
            Command command = switch (args[0]) {
                case "profile" -> Command.PROFILE;
                case "followers" -> Command.FOLLOWERS;
                case "following" -> Command.FOLLOWING;
                default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
            };
            if (args.length < 2 || args[1].startsWith("-")) {
                throw new IllegalArgumentException("Missing username for '" + args[0] + "'.");
            }

            String username = args[1];
            Integer limit = null;
            boolean all = false;
            boolean json = false;
            String cursor = null;
            for (int index = 2; index < args.length; index++) {
                switch (args[index]) {
                    case "--all" -> all = true;
                    case "--json" -> json = true;
                    case "--limit" -> limit = positive(value(args, ++index, "--limit"));
                    case "--cursor" -> cursor = value(args, ++index, "--cursor");
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            if (all && cursor != null) {
                // --all starts from the beginning and keeps going; a cursor says where to start.
                // Honouring both would silently ignore one, so say so instead.
                throw new IllegalArgumentException("--all and --cursor cannot be combined.");
            }
            if (command == Command.PROFILE && (limit != null || all || cursor != null)) {
                throw new IllegalArgumentException(
                        "'profile' returns one account; --limit, --all and --cursor do not apply.");
            }
            return new Arguments(command, username, limit, all, cursor, json, false);
        }

        private static String value(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " needs a value.");
            }
            return args[index];
        }

        private static Integer positive(String raw) {
            int value;
            try {
                value = Integer.parseInt(raw);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("--limit must be a number, got: " + raw);
            }
            if (value <= 0) {
                throw new IllegalArgumentException("--limit must be greater than zero.");
            }
            return value;
        }
    }
}
