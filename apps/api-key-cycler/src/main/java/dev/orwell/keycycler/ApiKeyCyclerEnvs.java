package dev.orwell.keycycler;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

/**
 * Every key this app reads is {@code CYCLER_*}, not {@code PROXY_*}, even where it means the same
 * thing the reverse proxy's version of it does. Sharing a name across two processes is how a
 * setting ends up applied to the one you were not thinking about; the repo renamed a whole
 * {@code DETECTION_*} family for this reason and does not need a second one.
 */
public final class ApiKeyCyclerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);

    public static final EnvOption<String> CYCLER_UPSTREAM_URL;
    public static final EnvOption<Integer> CYCLER_UPSTREAM_TIMEOUT_SECONDS;
    public static final EnvOption<Integer> CYCLER_MAX_BODY_BYTES;
    public static final EnvOption<String> CYCLER_API_KEYS;
    public static final EnvOption<String> CYCLER_KEY_HEADER;
    public static final EnvOption<String> CYCLER_KEY_SCHEME;
    public static final EnvOption<String> CYCLER_ROTATE_ON_STATUSES;
    public static final EnvOption<Integer> CYCLER_COOLDOWN_SECONDS;
    public static final EnvOption<Integer> CYCLER_MAX_ATTEMPTS;

    static {
        CYCLER_UPSTREAM_URL = ENV.required("CYCLER_UPSTREAM_URL", EnvType.string());
        CYCLER_UPSTREAM_TIMEOUT_SECONDS = ENV.optional("CYCLER_UPSTREAM_TIMEOUT_SECONDS", EnvType.integer(), 30);
        CYCLER_MAX_BODY_BYTES = ENV.optional("CYCLER_MAX_BODY_BYTES", EnvType.integer(), 10 * 1024 * 1024);

        // Required, because there is no sensible default for "no credentials": a cycler with an
        // empty pool forwards every request unauthenticated and turns one misconfiguration into a
        // stream of 401s the client cannot act on.
        //
        // This is a secret and it is declared as an ordinary option on purpose. Launching with the
        // `url` argument loads the whole environment from the secrets manager (see
        // dev.orwell.env.http.EnvLoader), so the deployment that must not have keys in a compose
        // file already has a way not to — without this app growing its own secret-fetching path.
        CYCLER_API_KEYS = ENV.required("CYCLER_API_KEYS", EnvType.string());

        // Two settings rather than one so the common shapes both work without string surgery:
        // `Authorization` + `Bearer` gives `Authorization: Bearer <key>`, and `X-API-Key` with an
        // empty scheme gives `X-API-Key: <key>`. A single "prefix" option would have to carry a
        // trailing space, which no .env file survives editing.
        CYCLER_KEY_HEADER = ENV.optional("CYCLER_KEY_HEADER", EnvType.string(), "Authorization");
        CYCLER_KEY_SCHEME = ENV.optional("CYCLER_KEY_SCHEME", EnvType.string(), "Bearer");

        // 429 is the one that matters; 401 and 403 are here because a revoked or suspended key
        // answers with those and is just as spent, and finding that out one request at a time is
        // what the pool exists to avoid.
        CYCLER_ROTATE_ON_STATUSES = ENV.optional("CYCLER_ROTATE_ON_STATUSES", EnvType.string(), "429,401,403");
        // How long a refused key rests. Longer than most rate-limit windows would idle capacity;
        // shorter turns the pool back into a queue on the first key.
        CYCLER_COOLDOWN_SECONDS = ENV.optional("CYCLER_COOLDOWN_SECONDS", EnvType.integer(), 60);
        // Upstream trips one client request may cost. Capped at the key count at startup — trying
        // more times than there are keys can only repeat one that has already refused.
        CYCLER_MAX_ATTEMPTS = ENV.optional("CYCLER_MAX_ATTEMPTS", EnvType.integer(), 3);

        ENV.property("cycler.upstream-url", CYCLER_UPSTREAM_URL);
        ENV.property("cycler.upstream-timeout-seconds", CYCLER_UPSTREAM_TIMEOUT_SECONDS);
        ENV.property("cycler.max-body-bytes", CYCLER_MAX_BODY_BYTES);
        ENV.property("cycler.api-keys", CYCLER_API_KEYS);
        ENV.property("cycler.key-header", CYCLER_KEY_HEADER);
        ENV.property("cycler.key-scheme", CYCLER_KEY_SCHEME);
        ENV.property("cycler.rotate-on-statuses", CYCLER_ROTATE_ON_STATUSES);
        ENV.property("cycler.cooldown-seconds", CYCLER_COOLDOWN_SECONDS);
        ENV.property("cycler.max-attempts", CYCLER_MAX_ATTEMPTS);
    }

    private ApiKeyCyclerEnvs() {
    }
}
