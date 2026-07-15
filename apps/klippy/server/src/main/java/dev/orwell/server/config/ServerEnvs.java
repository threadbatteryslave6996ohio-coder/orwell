package dev.orwell.server.config;

import dev.orwell.bootstrap.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class ServerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(true, true);
    public static final EnvOption<String> SPRING_DATASOURCE_URL;
    public static final EnvOption<String> SPRING_DATASOURCE_USERNAME;
    public static final EnvOption<String> SPRING_DATASOURCE_PASSWORD;
    public static final EnvOption<String> JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> JPA_JDBC_TIME_ZONE;

    static {
        SPRING_DATASOURCE_URL = ENV.required("SPRING_DATASOURCE_URL", EnvType.string());
        SPRING_DATASOURCE_USERNAME = ENV.required("SPRING_DATASOURCE_USERNAME", EnvType.string());
        SPRING_DATASOURCE_PASSWORD = ENV.required("SPRING_DATASOURCE_PASSWORD", EnvType.string());
        JPA_HIBERNATE_DDL_AUTO = ENV.required("SPRING_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        JPA_JDBC_TIME_ZONE = ENV.required("SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE", EnvType.string());
        ENV.property("spring.datasource.url", SPRING_DATASOURCE_URL);
        ENV.property("spring.datasource.username", SPRING_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", SPRING_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", JPA_HIBERNATE_DDL_AUTO);
        ENV.property("spring.jpa.properties.hibernate.jdbc.time_zone", JPA_JDBC_TIME_ZONE);
    }

    private ServerEnvs() {
    }
}
