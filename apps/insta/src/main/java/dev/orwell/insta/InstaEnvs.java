package dev.orwell.insta;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything this program reads from the environment.
 *
 * <p>Settings live here rather than in flags because they belong to the machine, not the question:
 * an API token, where Redis is, how much a lookup may spend. What varies per invocation — which
 * account, which direction, how many — is a command-line argument. {@code APIFY_TOKEN} is the only
 * required one, and {@link #load()} fails loudly if it is missing rather than discovering it
 * halfway through a paid run.
 *
 * <p>Values come from a {@code .env} file found upwards from the working directory, with real
 * environment variables taking precedence — the same rule the servers in this repo follow.
 */
public final class InstaEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> APIFY_TOKEN;
    public static final EnvOption<String> APIFY_BASE_URL;
    public static final EnvOption<String> APIFY_PROFILE_ACTOR;
    public static final EnvOption<String> APIFY_CONNECTIONS_ACTORS;
    public static final EnvOption<String> INSTA_INSTAGRAM_COOKIES;
    public static final EnvOption<Integer> APIFY_RUN_TIMEOUT_SECONDS;
    public static final EnvOption<Integer> INSTA_DEFAULT_LIMIT;
    public static final EnvOption<Integer> INSTA_MAX_LIMIT;
    public static final EnvOption<Boolean> INSTA_CACHE_ENABLED;
    public static final EnvOption<String> REDIS_HOST;
    public static final EnvOption<Integer> REDIS_PORT;
    public static final EnvOption<Boolean> INSTA_LOG_CONSOLE;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;
    public static final EnvOption<String> LOGGING_FILE_NAME;
    public static final EnvOption<String> INSTA_DATABASE_URL;
    public static final EnvOption<String> INSTA_DATABASE_USERNAME;
    public static final EnvOption<String> INSTA_DATABASE_PASSWORD;
    public static final EnvOption<String> INSTA_PICTURE_STORE;
    public static final EnvOption<String> INSTA_PICTURE_DIR;
    public static final EnvOption<String> INSTA_BUCKET_URL;
    public static final EnvOption<String> INSTA_BUCKET_TOKEN;
    public static final EnvOption<Integer> INSTA_MAX_RETIRE_PERCENT;
    public static final EnvOption<Integer> INSTA_SKIP_ABOVE_FOLLOWERS;
    public static final EnvOption<String> INSTA_UI_ADDRESS;
    public static final EnvOption<Integer> INSTA_UI_PORT;

    private static final EnvSchema SCHEMA;

    static {
        APIFY_TOKEN = BUILDER.required("APIFY_TOKEN", EnvType.string());
        APIFY_BASE_URL = BUILDER.optional("APIFY_BASE_URL", EnvType.string(), "https://api.apify.com");
        APIFY_PROFILE_ACTOR = BUILDER.optional(
                "APIFY_PROFILE_ACTOR", EnvType.string(), "apify/instagram-profile-scraper");
        // An ordered chain, not one actor: each has its own quota, and the next is tried when one
        // refuses. Names, not actor ids — every actor wants a different input shape, so each is an
        // adapter in the code. Known: scraping-solutions, datadoping, logical-scrapers.
        APIFY_CONNECTIONS_ACTORS = BUILDER.optional(
                "APIFY_CONNECTIONS_ACTORS", EnvType.string(), "scraping-solutions");
        // Only the logical-scrapers adapter uses these, and only if you set them: they are a live
        // Instagram session handed to a third-party actor.
        INSTA_INSTAGRAM_COOKIES = BUILDER.optional(
                "INSTA_INSTAGRAM_COOKIES", EnvType.string(), "");
        APIFY_RUN_TIMEOUT_SECONDS =
                BUILDER.optional("APIFY_RUN_TIMEOUT_SECONDS", EnvType.integer(), 120);
        // Raised to the ceiling: a lookup that reaches the end of a list in one run is cached as
        // the whole list and answers every later request for it, so asking for fewer than we are
        // allowed mostly buys a second run.
        INSTA_DEFAULT_LIMIT = BUILDER.optional("INSTA_DEFAULT_LIMIT", EnvType.integer(), 500);
        // Every result is billed by the actor, so the ceiling is a spend guard, not a page size.
        // 500 is what this deployment needs; a larger list also risks outrunning the run timeout.
        INSTA_MAX_LIMIT = BUILDER.optional("INSTA_MAX_LIMIT", EnvType.integer(), 500);
        INSTA_CACHE_ENABLED = BUILDER.optional("INSTA_CACHE_ENABLED", EnvType.bool(), true);
        // Same keys keeboarder-server uses, pointing at the one Redis in the stack.
        REDIS_HOST = BUILDER.optional("REDIS_HOST", EnvType.string(), "localhost");
        REDIS_PORT = BUILDER.optional("REDIS_PORT", EnvType.integer(), 6379);
        // Where log records go. See InstaLogger: unset means console only, which for a program
        // someone runs by hand is the ordinary case rather than a misconfiguration.
        INSTA_LOG_CONSOLE = BUILDER.optional("INSTA_LOG_CONSOLE", EnvType.bool(), true);
        // The same keys the Spring services use, so a deployment configures one app like another.
        LOKI_URL = BUILDER.optional("LOKI_URL", EnvType.string(), "");
        LOKI_TENANT_ID = BUILDER.optional("LOKI_TENANT_ID", EnvType.string(), "");
        LOGGING_FILE_NAME = BUILDER.optional("LOGGING_FILE_NAME", EnvType.string(), "");
        // Only `sync` needs a database, so these stay optional and that command reports a missing
        // one itself — requiring them here would break `profile` for anyone with no Postgres.
        INSTA_DATABASE_URL = BUILDER.optional("INSTA_DATABASE_URL", EnvType.string(), "");
        INSTA_DATABASE_USERNAME = BUILDER.optional("INSTA_DATABASE_USERNAME", EnvType.string(), "");
        INSTA_DATABASE_PASSWORD = BUILDER.optional("INSTA_DATABASE_PASSWORD", EnvType.string(), "");
        // Downloading someone's pictures should be something you asked for, so the default is off.
        INSTA_PICTURE_STORE = BUILDER.optional("INSTA_PICTURE_STORE", EnvType.string(), "none");
        INSTA_PICTURE_DIR = BUILDER.optional("INSTA_PICTURE_DIR", EnvType.string(), "");
        INSTA_BUCKET_URL = BUILDER.optional("INSTA_BUCKET_URL", EnvType.string(), "");
        INSTA_BUCKET_TOKEN = BUILDER.optional("INSTA_BUCKET_TOKEN", EnvType.string(), "");
        // A fuse, as a percentage: a walk that would retire more of an account's edges than this
        // is refusing to believe itself. A private account returns an empty list, and that is
        // indistinguishable from everyone leaving at once.
        INSTA_MAX_RETIRE_PERCENT = BUILDER.optional("INSTA_MAX_RETIRE_PERCENT", EnvType.integer(), 20);
        // The spend ceiling that actually bounds a crawl. `sync` walks a list to exhaustion, so
        // one popular account can cost more than every ordinary one put together — a 100k-follower
        // account is ~$60 at $0.60/1,000. Above this many, the walk is skipped entirely and only
        // the cheap profile lookup is paid for. 0 disables the guard.
        INSTA_SKIP_ABOVE_FOLLOWERS =
                BUILDER.optional("INSTA_SKIP_ABOVE_FOLLOWERS", EnvType.integer(), 1500);
        // The viewer binds every interface by default, as asked. It has no authentication, so
        // 127.0.0.1 is the setting that keeps it on this machine.
        INSTA_UI_ADDRESS = BUILDER.optional("INSTA_UI_ADDRESS", EnvType.string(), "0.0.0.0");
        INSTA_UI_PORT = BUILDER.optional("INSTA_UI_PORT", EnvType.integer(), 5554);

        SCHEMA = BUILDER.build();
    }

    private InstaEnvs() {
    }

    /**
     * @throws dev.orwell.env.EnvValidationException if a required variable is missing or a value
     *                                               does not parse.
     */
    public static Env load() throws IOException {
        return SCHEMA.from(EnvFiles.load());
    }
}
