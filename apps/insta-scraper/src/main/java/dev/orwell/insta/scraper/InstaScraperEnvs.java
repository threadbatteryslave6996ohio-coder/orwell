package dev.orwell.insta.scraper;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything the scraper reads from the environment.
 *
 * <p>Built on {@link EnvSchema} directly rather than on {@code AppServerEnv}: this is a plain
 * program with two Undertow routes, not a Spring server, so it has no engine to select and no
 * auth strategy to configure.
 *
 * <p>{@code INSTA_SCRAPER_SESSIONID} is the only required value, and it is required rather than
 * optional on purpose. Without a session this service can do nothing but return errors, and a
 * process that starts, looks healthy and fails every request is worse than one that refuses to
 * start — insta would fail over to a paid actor for every walk and the bill would be the only
 * symptom.
 */
public final class InstaScraperEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> SERVER_ADDRESS;
    public static final EnvOption<Integer> SERVER_PORT;
    public static final EnvOption<String> INSTA_SCRAPER_TOKEN;
    public static final EnvOption<String> INSTA_SCRAPER_SESSIONID;
    public static final EnvOption<String> INSTA_SCRAPER_DS_USER_ID;
    public static final EnvOption<String> INSTA_SCRAPER_CSRF_TOKEN;
    public static final EnvOption<String> INSTA_SCRAPER_EXTRA_COOKIES;
    public static final EnvOption<String> INSTA_SCRAPER_USER_AGENT;
    public static final EnvOption<String> INSTA_SCRAPER_APP_ID;
    public static final EnvOption<String> INSTA_SCRAPER_BASE_URL;
    public static final EnvOption<Integer> INSTA_SCRAPER_PAGE_SIZE;
    public static final EnvOption<Integer> INSTA_SCRAPER_MIN_INTERVAL_MILLIS;
    public static final EnvOption<Integer> INSTA_SCRAPER_HOURLY_REQUESTS;
    public static final EnvOption<Integer> INSTA_SCRAPER_MAX_SECONDS_PER_REQUEST;
    public static final EnvOption<String> LOGGER;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;

    private static final EnvSchema SCHEMA;

    static {
        SERVER_ADDRESS = BUILDER.optional("SERVER_ADDRESS", EnvType.string(), "127.0.0.1");
        SERVER_PORT = BUILDER.optional("SERVER_PORT", EnvType.integer(), 8099);

        // What insta sends as `Authorization: Bearer …` (INSTA_SELFHOSTED_TOKEN on its side).
        // Blank disables the check, which is only sane while this is bound to 127.0.0.1 — the
        // endpoint hands out follower graphs to anyone who can reach it.
        INSTA_SCRAPER_TOKEN = BUILDER.optional("INSTA_SCRAPER_TOKEN", EnvType.string(), "");

        // The session. sessionid alone is the login; the other two are sent alongside because
        // several endpoints check them and answer differently when they are missing.
        INSTA_SCRAPER_SESSIONID = BUILDER.required("INSTA_SCRAPER_SESSIONID", EnvType.string());
        INSTA_SCRAPER_DS_USER_ID = BUILDER.optional(
                "INSTA_SCRAPER_DS_USER_ID", EnvType.string(), "");
        INSTA_SCRAPER_CSRF_TOKEN = BUILDER.optional(
                "INSTA_SCRAPER_CSRF_TOKEN", EnvType.string(), "");
        // Anything else from the same jar — mid, ig_did — as `name=value; name=value`. Keeping
        // them stable across requests matters: they are device identity, and a session whose
        // device keeps changing is exactly what fraud detection looks for.
        INSTA_SCRAPER_EXTRA_COOKIES = BUILDER.optional(
                "INSTA_SCRAPER_EXTRA_COOKIES", EnvType.string(), "");

        // Must match the browser the session was issued to. The default is a current desktop
        // Chrome string; if your cookies came from a phone, put that phone's agent here instead.
        INSTA_SCRAPER_USER_AGENT = BUILDER.optional("INSTA_SCRAPER_USER_AGENT", EnvType.string(),
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/126.0.0.0 Safari/537.36");
        // The public web app id. Without X-IG-App-ID these endpoints answer 400.
        INSTA_SCRAPER_APP_ID = BUILDER.optional(
                "INSTA_SCRAPER_APP_ID", EnvType.string(), "936619743392459");
        // Overridable so the tests can point at a loopback stub instead of the real thing.
        INSTA_SCRAPER_BASE_URL = BUILDER.optional(
                "INSTA_SCRAPER_BASE_URL", EnvType.string(), "https://www.instagram.com");

        // Instagram's own page size. Asking for much more than 50 draws throttling sooner without
        // returning more, so this is a rate-limit setting wearing a pagination costume.
        INSTA_SCRAPER_PAGE_SIZE = BUILDER.optional(
                "INSTA_SCRAPER_PAGE_SIZE", EnvType.integer(), 50);
        // The two halves of the budget. The interval spaces requests out; the hourly cap bounds
        // how many there can be however patiently they are spaced.
        INSTA_SCRAPER_MIN_INTERVAL_MILLIS = BUILDER.optional(
                "INSTA_SCRAPER_MIN_INTERVAL_MILLIS", EnvType.integer(), 2000);
        INSTA_SCRAPER_HOURLY_REQUESTS = BUILDER.optional(
                "INSTA_SCRAPER_HOURLY_REQUESTS", EnvType.integer(), 200);
        // How long one /connections call may spend collecting pages before it returns what it has
        // with a cursor. Keep it under insta's INSTA_HTTP_TIMEOUT_SECONDS (60) or insta gives up
        // first and the work is wasted.
        INSTA_SCRAPER_MAX_SECONDS_PER_REQUEST = BUILDER.optional(
                "INSTA_SCRAPER_MAX_SECONDS_PER_REQUEST", EnvType.integer(), 40);

        LOGGER = BUILDER.optional("LOGGER", EnvType.string(), "");
        LOKI_URL = BUILDER.optional("LOKI_URL", EnvType.string(), "");
        LOKI_TENANT_ID = BUILDER.optional("LOKI_TENANT_ID", EnvType.string(), "");

        SCHEMA = BUILDER.build();
    }

    private InstaScraperEnvs() {
    }

    public static Env load() throws IOException {
        return SCHEMA.from(EnvFiles.load());
    }
}
