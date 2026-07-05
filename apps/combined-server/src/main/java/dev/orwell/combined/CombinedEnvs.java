package dev.orwell.combined;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

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
    public static final EnvOption<String> JARVIS_SERVER_ROUTE_PREFIX;
    public static final EnvOption<String> KEEBOARDER_SERVER_ROUTE_PREFIX;
    public static final EnvOption<String> SECRETS_ROUTE_PREFIX;
    public static final EnvOption<String> SECRETS_DATASOURCE_URL;
    public static final EnvOption<String> SECRETS_DATASOURCE_USERNAME;
    public static final EnvOption<String> SECRETS_DATASOURCE_PASSWORD;
    public static final EnvOption<String> SECRETS_JPA_HIBERNATE_DDL_AUTO;
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
        JARVIS_SERVER_ROUTE_PREFIX = builder.optional("JARVIS_SERVER_ROUTE_PREFIX", EnvType.string(), "/jarvis");
        KEEBOARDER_SERVER_ROUTE_PREFIX = builder.optional("KEEBOARDER_SERVER_ROUTE_PREFIX", EnvType.string(), "/keeboarder");
        SECRETS_ROUTE_PREFIX = builder.optional("SECRETS_ROUTE_PREFIX", EnvType.string(), "/secrets");
        SECRETS_DATASOURCE_URL = builder.required("SECRETS_DATASOURCE_URL", EnvType.string());
        SECRETS_DATASOURCE_USERNAME = builder.required("SECRETS_DATASOURCE_USERNAME", EnvType.string());
        SECRETS_DATASOURCE_PASSWORD = builder.required("SECRETS_DATASOURCE_PASSWORD", EnvType.string());
        SECRETS_JPA_HIBERNATE_DDL_AUTO = builder.required("SECRETS_JPA_HIBERNATE_DDL_AUTO", EnvType.string());
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
        return springProperties(env, Map.of());
    }

    static Map<String, Object> springProperties(Env env, Map<String, String> source) {
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
        values.put("jarvis.server.route-prefix", env.get(JARVIS_SERVER_ROUTE_PREFIX));
        values.put("keeboarder.server.route-prefix", env.get(KEEBOARDER_SERVER_ROUTE_PREFIX));
        values.put("secrets.route-prefix", env.get(SECRETS_ROUTE_PREFIX));
        values.put("secrets.datasource.url", env.get(SECRETS_DATASOURCE_URL));
        values.put("secrets.datasource.username", env.get(SECRETS_DATASOURCE_USERNAME));
        values.put("secrets.datasource.password", env.get(SECRETS_DATASOURCE_PASSWORD));
        values.put("secrets.jpa.hibernate.ddl-auto", env.get(SECRETS_JPA_HIBERNATE_DDL_AUTO));
        values.put("proxy.auth-server.base-url", env.get(CLIPPY_AUTH_BASE_URL));
        values.put("logging.file.name", env.get(LOGGING_FILE_NAME));
        values.put("clippy.auth.jpa.hibernate.ddl-auto", env.get(AUTH_JPA_HIBERNATE_DDL_AUTO));
        values.put("clippy.clipboard.jpa.hibernate.ddl-auto", env.get(CLIPBOARD_JPA_HIBERNATE_DDL_AUTO));
        values.put("clippy.jpa.jdbc-time-zone", env.get(JPA_JDBC_TIME_ZONE));
        copy(source, values, "PROXY_STORAGE_PROVIDER", "proxy.storage.provider");
        copy(source, values, "PROXY_STORAGE_MAX_FILE_SIZE", "proxy.storage.max-file-size");
        copy(source, values, "PROXY_S3_BUCKET_NAME", "proxy.s3.bucket-name");
        copy(source, values, "PROXY_S3_REGION", "proxy.s3.region");
        copy(source, values, "PROXY_S3_ENDPOINT", "proxy.s3.endpoint");
        copy(source, values, "PROXY_S3_PATH_STYLE_ACCESS", "proxy.s3.path-style-access");
        copy(source, values, "PROXY_S3_SSE", "proxy.s3.server-side-encryption");
        copy(source, values, "AZURE_STORAGE_ACCOUNT", "proxy.azure.account-name");
        copy(source, values, "AZURE_STORAGE_CONTAINER", "proxy.azure.container-name");
        copy(source, values, "AZURE_STORAGE_ENDPOINT", "proxy.azure.endpoint");
        copy(source, values, "AZURE_STORAGE_CONNECTION_STRING", "proxy.azure.connection-string");
        copy(source, values, "AUTH_IDENTITY_PROVISIONING_KEY", "proxy.auth-server.identity-provisioning-key");
        copy(source, values, "PROXY_MANAGEMENT_USERNAME", "proxy.management.username");
        copy(source, values, "PROXY_MANAGEMENT_PASSWORD", "proxy.management.password");
        copy(source, values, "PROXY_MANAGEMENT_SESSION_SECRET", "proxy.management.session-secret");
        copy(source, values, "PROXY_SERVER_URL", "proxy.server.url");
        copy(source, values, "PROXY_AUDIT_FILE", "proxy.logging.audit-file");
        copy(source, values, "WEBSOCKET_HOST", "keeboarder.websocket.host");
        copy(source, values, "WEBSOCKET_PORT", "keeboarder.websocket.port");
        copy(source, values, "REDIS_HOST", "keeboarder.redis.host");
        copy(source, values, "REDIS_PORT", "keeboarder.redis.port");
        return values;
    }

    private static void copy(
            Map<String, String> source,
            Map<String, Object> destination,
            String environmentName,
            String propertyName
    ) {
        String value = source.get(environmentName);
        if (value != null && !value.isBlank()) {
            destination.put(propertyName, value.trim());
        }
    }

}
