package dev.orwell.reverseproxy;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class ReverseProxyEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);

    public static final EnvOption<String> PROXY_UPSTREAM_URL;
    public static final EnvOption<String> PROXY_BLOCKED_PATTERNS;
    public static final EnvOption<Integer> PROXY_UPSTREAM_TIMEOUT_SECONDS;
    public static final EnvOption<Integer> PROXY_MAX_BODY_BYTES;

    static {
        PROXY_UPSTREAM_URL = ENV.required("PROXY_UPSTREAM_URL", EnvType.string());
        PROXY_BLOCKED_PATTERNS = ENV.optional("PROXY_BLOCKED_PATTERNS", EnvType.string(), "");
        PROXY_UPSTREAM_TIMEOUT_SECONDS = ENV.optional("PROXY_UPSTREAM_TIMEOUT_SECONDS", EnvType.integer(), 30);
        PROXY_MAX_BODY_BYTES = ENV.optional("PROXY_MAX_BODY_BYTES", EnvType.integer(), 10 * 1024 * 1024);

        ENV.property("proxy.upstream-url", PROXY_UPSTREAM_URL);
        ENV.property("proxy.blocked-patterns", PROXY_BLOCKED_PATTERNS);
        ENV.property("proxy.upstream-timeout-seconds", PROXY_UPSTREAM_TIMEOUT_SECONDS);
        ENV.property("proxy.max-body-bytes", PROXY_MAX_BODY_BYTES);
    }

    private ReverseProxyEnvs() {
    }
}
