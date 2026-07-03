package dev.clippy.server;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class ServerEnvs {
    public static final EnvOption<String> SPRING_DATASOURCE_URL;
    public static final EnvOption<String> SPRING_DATASOURCE_USERNAME;
    public static final EnvOption<String> SPRING_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SERVER_PORT;
    public static final EnvOption<String> CLIPPY_AUTH_BASE_URL;
    public static final EnvOption<String> LOGGING_FILE_NAME;
    public static final EnvOption<String> JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> JPA_JDBC_TIME_ZONE;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        SPRING_DATASOURCE_URL = builder.required("SPRING_DATASOURCE_URL", EnvType.string());
        SPRING_DATASOURCE_USERNAME = builder.required("SPRING_DATASOURCE_USERNAME", EnvType.string());
        SPRING_DATASOURCE_PASSWORD = builder.required("SPRING_DATASOURCE_PASSWORD", EnvType.string());
        SERVER_PORT = builder.required("SERVER_PORT", EnvType.string());
        CLIPPY_AUTH_BASE_URL = builder.required("CLIPPY_AUTH_BASE_URL", EnvType.string());
        LOGGING_FILE_NAME = builder.required("LOGGING_FILE_NAME", EnvType.string());
        JPA_HIBERNATE_DDL_AUTO = builder.required("SPRING_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        JPA_JDBC_TIME_ZONE = builder.required("SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_TIME_ZONE", EnvType.string());
        ENV = builder.build();
    }

    private ServerEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("spring.datasource.url", env.get(SPRING_DATASOURCE_URL));
        values.put("spring.datasource.username", env.get(SPRING_DATASOURCE_USERNAME));
        values.put("spring.datasource.password", env.get(SPRING_DATASOURCE_PASSWORD));
        values.put("server.port", env.get(SERVER_PORT));
        values.put("clippy.auth.base-url", env.get(CLIPPY_AUTH_BASE_URL));
        values.put("logging.file.name", env.get(LOGGING_FILE_NAME));
        values.put("spring.jpa.hibernate.ddl-auto", env.get(JPA_HIBERNATE_DDL_AUTO));
        values.put("spring.jpa.properties.hibernate.jdbc.time_zone", env.get(JPA_JDBC_TIME_ZONE));
        return values;
    }
}
