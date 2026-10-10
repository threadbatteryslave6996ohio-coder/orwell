package dev.orwell.auth.http.server.config;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class AuthServerEnvs {
    // LOGGING_FILE_NAME is optional: this server logs to console plus Loki like every other one,
    // and nothing here writes an app log file. AUTH_BASE_URL is optional because the auth server
    // is the thing other servers point at — it never checks a token against itself.
    public static final AppServerEnv ENV = new AppServerEnv(false, false);
    public static final EnvOption<String> AUTH_DATASOURCE_URL;
    public static final EnvOption<String> AUTH_DATASOURCE_USERNAME;
    public static final EnvOption<String> AUTH_DATASOURCE_PASSWORD;
    public static final EnvOption<String> AUTH_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> AUTH_JPA_JDBC_TIME_ZONE;
    public static final EnvOption<String> AUTH_ROUTE_PREFIX;
    public static final EnvOption<String> AUTH_ADMIN_BOOTSTRAP_CLIENT_ID;
    public static final EnvOption<String> AUTH_ADMIN_BOOTSTRAP_SECRET;
    public static final EnvOption<Boolean> AUTH_TOKEN_CLEANUP_ENABLED;
    public static final EnvOption<Integer> AUTH_TOKEN_CLEANUP_INTERVAL_SECONDS;
    public static final EnvOption<Integer> AUTH_TOKEN_CLEANUP_IDLE_SECONDS;

    static {
        AUTH_DATASOURCE_URL = ENV.required("AUTH_DATASOURCE_URL", EnvType.string());
        AUTH_DATASOURCE_USERNAME = ENV.required("AUTH_DATASOURCE_USERNAME", EnvType.string());
        AUTH_DATASOURCE_PASSWORD = ENV.required("AUTH_DATASOURCE_PASSWORD", EnvType.string());
        AUTH_JPA_HIBERNATE_DDL_AUTO = ENV.required("AUTH_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        AUTH_JPA_JDBC_TIME_ZONE = ENV.required("AUTH_JPA_JDBC_TIME_ZONE", EnvType.string());
        AUTH_ROUTE_PREFIX = ENV.optional("AUTH_ROUTE_PREFIX", EnvType.string(), "");
        // Optional, and meant to be removed again: it seeds the one admin identity that cannot be
        // created over HTTP. See AdminBootstrap.
        AUTH_ADMIN_BOOTSTRAP_CLIENT_ID = ENV.optional("AUTH_ADMIN_BOOTSTRAP_CLIENT_ID", EnvType.string(), "");
        AUTH_ADMIN_BOOTSTRAP_SECRET = ENV.optional("AUTH_ADMIN_BOOTSTRAP_SECRET", EnvType.string(), "");
        // On by default: client_tokens is append-only otherwise, and a server nobody configured is
        // exactly the one that has been accumulating rows since it was deployed. See TokenCleanupJob.
        AUTH_TOKEN_CLEANUP_ENABLED = ENV.optional("AUTH_TOKEN_CLEANUP_ENABLED", EnvType.bool(), true);
        AUTH_TOKEN_CLEANUP_INTERVAL_SECONDS =
                ENV.optional("AUTH_TOKEN_CLEANUP_INTERVAL_SECONDS", EnvType.integer(), 3600);
        AUTH_TOKEN_CLEANUP_IDLE_SECONDS =
                ENV.optional("AUTH_TOKEN_CLEANUP_IDLE_SECONDS", EnvType.integer(), 86400);
        ENV.property("spring.datasource.url", AUTH_DATASOURCE_URL);
        ENV.property("spring.datasource.username", AUTH_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", AUTH_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", AUTH_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("spring.jpa.properties.hibernate.jdbc.time_zone", AUTH_JPA_JDBC_TIME_ZONE);
        ENV.property("orwell.auth.route-prefix", AUTH_ROUTE_PREFIX);
        ENV.property("orwell.auth.admin-bootstrap.client-id", AUTH_ADMIN_BOOTSTRAP_CLIENT_ID);
        ENV.property("orwell.auth.admin-bootstrap.secret", AUTH_ADMIN_BOOTSTRAP_SECRET);
        ENV.property("orwell.auth.token-cleanup.enabled", AUTH_TOKEN_CLEANUP_ENABLED);
        ENV.property("orwell.auth.token-cleanup.interval-seconds", AUTH_TOKEN_CLEANUP_INTERVAL_SECONDS);
        ENV.property("orwell.auth.token-cleanup.idle-seconds", AUTH_TOKEN_CLEANUP_IDLE_SECONDS);
    }

    private AuthServerEnvs() {
    }
}
