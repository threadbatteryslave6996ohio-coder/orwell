package dev.clippy.auth;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class AuthServerEnvs {
    public static final EnvOption<String> AUTH_DATASOURCE_URL;
    public static final EnvOption<String> AUTH_DATASOURCE_USERNAME;
    public static final EnvOption<String> AUTH_DATASOURCE_PASSWORD;
    public static final EnvOption<String> AUTH_SERVER_PORT;
    public static final EnvOption<String> AUTH_LOGGING_FILE_NAME;
    public static final EnvOption<String> AUTH_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> AUTH_JPA_JDBC_TIME_ZONE;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        AUTH_DATASOURCE_URL = builder.required("AUTH_DATASOURCE_URL", EnvType.string());
        AUTH_DATASOURCE_USERNAME = builder.required("AUTH_DATASOURCE_USERNAME", EnvType.string());
        AUTH_DATASOURCE_PASSWORD = builder.required("AUTH_DATASOURCE_PASSWORD", EnvType.string());
        AUTH_SERVER_PORT = builder.required("AUTH_SERVER_PORT", EnvType.string());
        AUTH_LOGGING_FILE_NAME = builder.required("AUTH_LOGGING_FILE_NAME", EnvType.string());
        AUTH_JPA_HIBERNATE_DDL_AUTO = builder.required("AUTH_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        AUTH_JPA_JDBC_TIME_ZONE = builder.required("AUTH_JPA_JDBC_TIME_ZONE", EnvType.string());
        ENV = builder.build();
    }

    private AuthServerEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("spring.datasource.url", env.get(AUTH_DATASOURCE_URL));
        values.put("spring.datasource.username", env.get(AUTH_DATASOURCE_USERNAME));
        values.put("spring.datasource.password", env.get(AUTH_DATASOURCE_PASSWORD));
        values.put("server.port", env.get(AUTH_SERVER_PORT));
        values.put("logging.file.name", env.get(AUTH_LOGGING_FILE_NAME));
        values.put("spring.jpa.hibernate.ddl-auto", env.get(AUTH_JPA_HIBERNATE_DDL_AUTO));
        values.put("spring.jpa.properties.hibernate.jdbc.time_zone", env.get(AUTH_JPA_JDBC_TIME_ZONE));
        return values;
    }
}
