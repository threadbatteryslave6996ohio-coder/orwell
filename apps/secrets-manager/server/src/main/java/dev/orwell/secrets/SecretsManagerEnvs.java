package dev.orwell.secrets;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class SecretsManagerEnvs {
    public static final EnvOption<String> SECRETS_DATASOURCE_URL;
    public static final EnvOption<String> SECRETS_DATASOURCE_USERNAME;
    public static final EnvOption<String> SECRETS_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SECRETS_SERVER_PORT;
    public static final EnvOption<String> SECRETS_LOGGING_FILE_NAME;
    public static final EnvOption<String> SECRETS_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> SECRETS_JPA_JDBC_TIME_ZONE;
    public static final EnvOption<String> SECRETS_AUTH_BASE_URL;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        SECRETS_DATASOURCE_URL = builder.required("SECRETS_DATASOURCE_URL", EnvType.string());
        SECRETS_DATASOURCE_USERNAME = builder.required("SECRETS_DATASOURCE_USERNAME", EnvType.string());
        SECRETS_DATASOURCE_PASSWORD = builder.required("SECRETS_DATASOURCE_PASSWORD", EnvType.string());
        SECRETS_SERVER_PORT = builder.required("SECRETS_SERVER_PORT", EnvType.string());
        SECRETS_LOGGING_FILE_NAME = builder.required("SECRETS_LOGGING_FILE_NAME", EnvType.string());
        SECRETS_JPA_HIBERNATE_DDL_AUTO = builder.required("SECRETS_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        SECRETS_JPA_JDBC_TIME_ZONE = builder.required("SECRETS_JPA_JDBC_TIME_ZONE", EnvType.string());
        SECRETS_AUTH_BASE_URL = builder.required("SECRETS_AUTH_BASE_URL", EnvType.string());
        ENV = builder.build();
    }

    private SecretsManagerEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("spring.datasource.url", env.get(SECRETS_DATASOURCE_URL));
        values.put("spring.datasource.username", env.get(SECRETS_DATASOURCE_USERNAME));
        values.put("spring.datasource.password", env.get(SECRETS_DATASOURCE_PASSWORD));
        values.put("server.port", env.get(SECRETS_SERVER_PORT));
        values.put("logging.file.name", env.get(SECRETS_LOGGING_FILE_NAME));
        values.put("spring.jpa.hibernate.ddl-auto", env.get(SECRETS_JPA_HIBERNATE_DDL_AUTO));
        values.put("spring.jpa.properties.hibernate.jdbc.time_zone", env.get(SECRETS_JPA_JDBC_TIME_ZONE));
        values.put("secrets.auth.base-url", env.get(SECRETS_AUTH_BASE_URL));
        return values;
    }
}
