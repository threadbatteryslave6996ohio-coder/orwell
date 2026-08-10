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

    static {
        AUTH_DATASOURCE_URL = ENV.required("AUTH_DATASOURCE_URL", EnvType.string());
        AUTH_DATASOURCE_USERNAME = ENV.required("AUTH_DATASOURCE_USERNAME", EnvType.string());
        AUTH_DATASOURCE_PASSWORD = ENV.required("AUTH_DATASOURCE_PASSWORD", EnvType.string());
        AUTH_JPA_HIBERNATE_DDL_AUTO = ENV.required("AUTH_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        AUTH_JPA_JDBC_TIME_ZONE = ENV.required("AUTH_JPA_JDBC_TIME_ZONE", EnvType.string());
        AUTH_ROUTE_PREFIX = ENV.optional("AUTH_ROUTE_PREFIX", EnvType.string(), "");
        ENV.property("spring.datasource.url", AUTH_DATASOURCE_URL);
        ENV.property("spring.datasource.username", AUTH_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", AUTH_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", AUTH_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("spring.jpa.properties.hibernate.jdbc.time_zone", AUTH_JPA_JDBC_TIME_ZONE);
        ENV.property("orwell.auth.route-prefix", AUTH_ROUTE_PREFIX);
    }

    private AuthServerEnvs() {
    }
}
