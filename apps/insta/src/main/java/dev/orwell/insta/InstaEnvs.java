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
    public static final EnvOption<String> APIFY_CONNECTIONS_ACTOR;
    public static final EnvOption<Integer> APIFY_RUN_TIMEOUT_SECONDS;
    public static final EnvOption<Integer> INSTA_DEFAULT_LIMIT;
    public static final EnvOption<Integer> INSTA_MAX_LIMIT;
    public static final EnvOption<Boolean> INSTA_CACHE_ENABLED;
    public static final EnvOption<Integer> INSTA_CACHE_TTL_HOURS;
    public static final EnvOption<String> REDIS_HOST;
    public static final EnvOption<Integer> REDIS_PORT;
    public static final EnvOption<Boolean> INSTA_LOG_CONSOLE;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;
    public static final EnvOption<String> LOGGING_FILE_NAME;

    private static final EnvSchema SCHEMA;

    static {
        APIFY_TOKEN = BUILDER.required("APIFY_TOKEN", EnvType.string());
        APIFY_BASE_URL = BUILDER.optional("APIFY_BASE_URL", EnvType.string(), "https://api.apify.com");
        APIFY_PROFILE_ACTOR = BUILDER.optional(
                "APIFY_PROFILE_ACTOR", EnvType.string(), "apify/instagram-profile-scraper");
        APIFY_CONNECTIONS_ACTOR = BUILDER.optional("APIFY_CONNECTIONS_ACTOR", EnvType.string(),
                "scraping_solutions/instagram-scraper-followers-following-no-cookies");
        APIFY_RUN_TIMEOUT_SECONDS =
                BUILDER.optional("APIFY_RUN_TIMEOUT_SECONDS", EnvType.integer(), 120);
        INSTA_DEFAULT_LIMIT = BUILDER.optional("INSTA_DEFAULT_LIMIT", EnvType.integer(), 100);
        // Every result is billed by the actor, so the ceiling is a spend guard, not a page size.
        // 500 is what this deployment needs; a larger list also risks outrunning the run timeout.
        INSTA_MAX_LIMIT = BUILDER.optional("INSTA_MAX_LIMIT", EnvType.integer(), 500);
        INSTA_CACHE_ENABLED = BUILDER.optional("INSTA_CACHE_ENABLED", EnvType.bool(), true);
        INSTA_CACHE_TTL_HOURS = BUILDER.optional("INSTA_CACHE_TTL_HOURS", EnvType.integer(), 24);
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
