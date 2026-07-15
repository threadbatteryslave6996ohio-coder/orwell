package dev.orwell.secrets;

import dev.orwell.bootstrap.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class SecretsManagerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(true, true);
    public static final EnvOption<String> SECRETS_DATASOURCE_URL;
    public static final EnvOption<String> SECRETS_DATASOURCE_USERNAME;
    public static final EnvOption<String> SECRETS_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SECRETS_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> SECRETS_JPA_JDBC_TIME_ZONE;

    static {
        SECRETS_DATASOURCE_URL = ENV.required("SECRETS_DATASOURCE_URL", EnvType.string());
        SECRETS_DATASOURCE_USERNAME = ENV.required("SECRETS_DATASOURCE_USERNAME", EnvType.string());
        SECRETS_DATASOURCE_PASSWORD = ENV.required("SECRETS_DATASOURCE_PASSWORD", EnvType.string());
        SECRETS_JPA_HIBERNATE_DDL_AUTO = ENV.required("SECRETS_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        SECRETS_JPA_JDBC_TIME_ZONE = ENV.required("SECRETS_JPA_JDBC_TIME_ZONE", EnvType.string());
        ENV.property("spring.datasource.url", SECRETS_DATASOURCE_URL);
        ENV.property("spring.datasource.username", SECRETS_DATASOURCE_USERNAME);
        ENV.property("spring.datasource.password", SECRETS_DATASOURCE_PASSWORD);
        ENV.property("spring.jpa.hibernate.ddl-auto", SECRETS_JPA_HIBERNATE_DDL_AUTO);
        ENV.property("spring.jpa.properties.hibernate.jdbc.time_zone", SECRETS_JPA_JDBC_TIME_ZONE);
    }

    private SecretsManagerEnvs() {
    }
}
