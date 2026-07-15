package dev.orwell.auth.http.server.config;

import dev.orwell.bootstrap.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class AuthServerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(true, false);
    public static final EnvOption<String> AUTH_DATASOURCE_URL;
    public static final EnvOption<String> AUTH_DATASOURCE_USERNAME;
    public static final EnvOption<String> AUTH_DATASOURCE_PASSWORD;
    public static final EnvOption<String> AUTH_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> AUTH_JPA_JDBC_TIME_ZONE;

    static {
        AUTH_DATASOURCE_URL = ENV.required("AUTH_DATASOURCE_URL", EnvType.string());
        AUTH_DATASOURCE_USERNAME = ENV.required("AUTH_DATASOURCE_USERNAME", EnvType.string());
        AUTH_DATASOURCE_PASSWORD = ENV.required("AUTH_DATASOURCE_PASSWORD", EnvType.string());
        AUTH_JPA_HIBERNATE_DDL_AUTO = ENV.required("AUTH_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        AUTH_JPA_JDBC_TIME_ZONE = ENV.required("AUTH_JPA_JDBC_TIME_ZONE", EnvType.string());
        ENV.property("spring.datasource.url", AUTH_DATASOURCE_URL);
        ENV.property("spring.datasource.username", AUTH_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", AUTH_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", AUTH_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("spring.jpa.properties.hibernate.jdbc.time_zone", AUTH_JPA_JDBC_TIME_ZONE);
    }

    private AuthServerEnvs() {
    }
}
