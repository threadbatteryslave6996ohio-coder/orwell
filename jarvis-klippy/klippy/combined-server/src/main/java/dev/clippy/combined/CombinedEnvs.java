package dev.clippy.combined;

import dev.clippy.utils.envmanager.Env;
import dev.clippy.utils.envmanager.EnvOption;
import dev.clippy.utils.envmanager.EnvSchema;
import dev.clippy.utils.envmanager.EnvType;

import java.util.LinkedHashMap;
import java.util.Map;

public final class CombinedEnvs {
    public static final EnvOption<String> COMBINED_SERVER_PORT;
    public static final EnvOption<String> AUTH_DATASOURCE_URL;
    public static final EnvOption<String> AUTH_DATASOURCE_USERNAME;
    public static final EnvOption<String> AUTH_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SPRING_DATASOURCE_URL;
    public static final EnvOption<String> SPRING_DATASOURCE_USERNAME;
    public static final EnvOption<String> SPRING_DATASOURCE_PASSWORD;
    public static final EnvOption<String> CLIPPY_AUTH_BASE_URL;
    public static final EnvOption<String> CLIPPY_AUTH_ROUTE_PREFIX;
    public static final EnvOption<String> CLIPPY_SERVER_ROUTE_PREFIX;
    public static final EnvOption<String> LOGGING_FILE_NAME;
    public static final EnvOption<String> AUTH_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> CLIPBOARD_JPA_HIBERNATE_DDL_AUTO;
    public static final EnvOption<String> JPA_JDBC_TIME_ZONE;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        COMBINED_SERVER_PORT = builder.required("COMBINED_SERVER_PORT", EnvType.string());
        AUTH_DATASOURCE_URL = builder.required("AUTH_DATASOURCE_URL", EnvType.string());
        AUTH_DATASOURCE_USERNAME = builder.required("AUTH_DATASOURCE_USERNAME", EnvType.string());
        AUTH_DATASOURCE_PASSWORD = builder.required("AUTH_DATASOURCE_PASSWORD", EnvType.string());
        SPRING_DATASOURCE_URL = builder.required("SPRING_DATASOURCE_URL", EnvType.string());
        SPRING_DATASOURCE_USERNAME = builder.required("SPRING_DATASOURCE_USERNAME", EnvType.string());
        SPRING_DATASOURCE_PASSWORD = builder.required("SPRING_DATASOURCE_PASSWORD", EnvType.string());
        CLIPPY_AUTH_BASE_URL = builder.required("CLIPPY_AUTH_BASE_URL", EnvType.string());
        CLIPPY_AUTH_ROUTE_PREFIX = builder.required("CLIPPY_AUTH_ROUTE_PREFIX", EnvType.string());
        CLIPPY_SERVER_ROUTE_PREFIX = builder.required("CLIPPY_SERVER_ROUTE_PREFIX", EnvType.string());
        LOGGING_FILE_NAME = builder.required("LOGGING_FILE_NAME", EnvType.string());
        AUTH_JPA_HIBERNATE_DDL_AUTO = builder.required("AUTH_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        CLIPBOARD_JPA_HIBERNATE_DDL_AUTO = builder.required("CLIPBOARD_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
        JPA_JDBC_TIME_ZONE = builder.required("JPA_JDBC_TIME_ZONE", EnvType.string());
        ENV = builder.build();
    }

    private CombinedEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("server.port", env.get(COMBINED_SERVER_PORT));
        values.put("clippy.auth.datasource.url", env.get(AUTH_DATASOURCE_URL));
        values.put("clippy.auth.datasource.username", env.get(AUTH_DATASOURCE_USERNAME));
        values.put("clippy.auth.datasource.password", env.get(AUTH_DATASOURCE_PASSWORD));
        values.put("spring.datasource.url", env.get(SPRING_DATASOURCE_URL));
        values.put("spring.datasource.username", env.get(SPRING_DATASOURCE_USERNAME));
        values.put("spring.datasource.password", env.get(SPRING_DATASOURCE_PASSWORD));
        values.put("clippy.auth.base-url", env.get(CLIPPY_AUTH_BASE_URL));
        values.put("clippy.auth.route-prefix", env.get(CLIPPY_AUTH_ROUTE_PREFIX));
        values.put("clippy.server.route-prefix", env.get(CLIPPY_SERVER_ROUTE_PREFIX));
        values.put("logging.file.name", env.get(LOGGING_FILE_NAME));
        values.put("clippy.auth.jpa.hibernate.ddl-auto", env.get(AUTH_JPA_HIBERNATE_DDL_AUTO));
        values.put("clippy.clipboard.jpa.hibernate.ddl-auto", env.get(CLIPBOARD_JPA_HIBERNATE_DDL_AUTO));
        values.put("clippy.jpa.jdbc-time-zone", env.get(JPA_JDBC_TIME_ZONE));
        return values;
    }

}
