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
    public static final EnvOption<String> PROXY_AUDIT_DATASOURCE_URL;
    public static final EnvOption<String> PROXY_AUDIT_DATASOURCE_USERNAME;
    public static final EnvOption<String> PROXY_AUDIT_DATASOURCE_PASSWORD;
    public static final EnvOption<Integer> PROXY_AUDIT_QUEUE_DEPTH;
    public static final EnvOption<Integer> PROXY_AUDIT_RETENTION_DAYS;

    static {
        PROXY_UPSTREAM_URL = ENV.required("PROXY_UPSTREAM_URL", EnvType.string());
        PROXY_BLOCKED_PATTERNS = ENV.optional("PROXY_BLOCKED_PATTERNS", EnvType.string(), "");
        PROXY_UPSTREAM_TIMEOUT_SECONDS = ENV.optional("PROXY_UPSTREAM_TIMEOUT_SECONDS", EnvType.integer(), 30);
        PROXY_MAX_BODY_BYTES = ENV.optional("PROXY_MAX_BODY_BYTES", EnvType.integer(), 10 * 1024 * 1024);

        // Optional, unlike jarvis-hub's HUB_DATASOURCE_URL: the hub cannot replay a frame it did
        // not store, while the proxy still proxies — and still logs every request — with nowhere
        // to store the trail. Unset means "log only", announced with a warning at startup so it
        // cannot be mistaken for a working audit table. See db-init/all-services.sql for the
        // `proxy` role and database.
        PROXY_AUDIT_DATASOURCE_URL = ENV.optional("PROXY_AUDIT_DATASOURCE_URL", EnvType.string(), "");
        PROXY_AUDIT_DATASOURCE_USERNAME = ENV.optional("PROXY_AUDIT_DATASOURCE_USERNAME", EnvType.string(), "");
        PROXY_AUDIT_DATASOURCE_PASSWORD = ENV.optional("PROXY_AUDIT_DATASOURCE_PASSWORD", EnvType.string(), "");
        // Records buffered ahead of the writer. Deep enough to ride out a slow commit or a
        // restarting database, bounded because the request path must never wait on either.
        PROXY_AUDIT_QUEUE_DEPTH = ENV.optional("PROXY_AUDIT_QUEUE_DEPTH", EnvType.integer(), 10_000);
        // How long rows are kept, swept hourly by the writer thread. 0 keeps them forever, which
        // is a deliberate choice on a shared Postgres rather than a default.
        PROXY_AUDIT_RETENTION_DAYS = ENV.optional("PROXY_AUDIT_RETENTION_DAYS", EnvType.integer(), 30);

        ENV.property("proxy.upstream-url", PROXY_UPSTREAM_URL);
        ENV.property("proxy.blocked-patterns", PROXY_BLOCKED_PATTERNS);
        ENV.property("proxy.upstream-timeout-seconds", PROXY_UPSTREAM_TIMEOUT_SECONDS);
        ENV.property("proxy.max-body-bytes", PROXY_MAX_BODY_BYTES);
        ENV.property("proxy.audit.datasource-url", PROXY_AUDIT_DATASOURCE_URL);
        ENV.property("proxy.audit.datasource-username", PROXY_AUDIT_DATASOURCE_USERNAME);
        ENV.property("proxy.audit.datasource-password", PROXY_AUDIT_DATASOURCE_PASSWORD);
        ENV.property("proxy.audit.queue-depth", PROXY_AUDIT_QUEUE_DEPTH);
        ENV.property("proxy.audit.retention-days", PROXY_AUDIT_RETENTION_DAYS);
    }

    private ReverseProxyEnvs() {
    }
}
