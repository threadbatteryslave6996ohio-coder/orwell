package dev.orwell.bucket.proxy;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class JarvisProxyEnvs {
    public static final EnvOption<String> PROXY_STORAGE_PROVIDER;
    public static final EnvOption<Long> PROXY_STORAGE_MAX_FILE_SIZE;
    public static final EnvOption<String> PROXY_S3_BUCKET_NAME;
    public static final EnvOption<String> PROXY_S3_REGION;
    public static final EnvOption<String> PROXY_S3_ENDPOINT;
    public static final EnvOption<Boolean> PROXY_S3_PATH_STYLE_ACCESS;
    public static final EnvOption<String> PROXY_S3_SSE;
    public static final EnvOption<String> AZURE_STORAGE_ACCOUNT;
    public static final EnvOption<String> AZURE_STORAGE_CONTAINER;
    public static final EnvOption<String> AZURE_STORAGE_ENDPOINT;
    public static final EnvOption<String> AZURE_STORAGE_CONNECTION_STRING;
    public static final EnvOption<String> PROXY_AUTH_SERVER_BASE_URL;
    public static final EnvOption<String> AUTH_IDENTITY_PROVISIONING_KEY;
    public static final EnvOption<String> PROXY_MANAGEMENT_USERNAME;
    public static final EnvOption<String> PROXY_MANAGEMENT_PASSWORD;
    public static final EnvOption<String> PROXY_MANAGEMENT_SESSION_SECRET;
    public static final EnvOption<String> PROXY_CORS_ALLOWED_ORIGINS;
    public static final EnvOption<String> PROXY_LOGGING_AUDIT_FILE;
    public static final EnvOption<String> PROXY_SERVER_URL;
    public static final EnvOption<String> STREAM_ANALYSIS_ENDPOINT;
    public static final EnvOption<String> SERVER_PORT;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        PROXY_STORAGE_PROVIDER = builder.optional("PROXY_STORAGE_PROVIDER", EnvType.string(), "aws");
        PROXY_STORAGE_MAX_FILE_SIZE = builder.optional("PROXY_STORAGE_MAX_FILE_SIZE", EnvType.longInteger(), 5368709120L);
        PROXY_S3_BUCKET_NAME = builder.optional("PROXY_S3_BUCKET_NAME", EnvType.string(), "your-bucket-name");
        PROXY_S3_REGION = builder.optional("PROXY_S3_REGION", EnvType.string(), "us-east-1");
        PROXY_S3_ENDPOINT = builder.optional("PROXY_S3_ENDPOINT", EnvType.string(), "");
        PROXY_S3_PATH_STYLE_ACCESS = builder.optional("PROXY_S3_PATH_STYLE_ACCESS", EnvType.bool(), false);
        PROXY_S3_SSE = builder.optional("PROXY_S3_SSE", EnvType.string(), "AES256");
        AZURE_STORAGE_ACCOUNT = builder.optional("AZURE_STORAGE_ACCOUNT", EnvType.string(), "");
        AZURE_STORAGE_CONTAINER = builder.optional("AZURE_STORAGE_CONTAINER", EnvType.string(), "");
        AZURE_STORAGE_ENDPOINT = builder.optional("AZURE_STORAGE_ENDPOINT", EnvType.string(), "");
        AZURE_STORAGE_CONNECTION_STRING = builder.optional("AZURE_STORAGE_CONNECTION_STRING", EnvType.string(), "");
        PROXY_AUTH_SERVER_BASE_URL = builder.optional("PROXY_AUTH_SERVER_BASE_URL", EnvType.string(), "http://localhost:8081");
        AUTH_IDENTITY_PROVISIONING_KEY = builder.optional("AUTH_IDENTITY_PROVISIONING_KEY", EnvType.string(), "");
        PROXY_MANAGEMENT_USERNAME = builder.optional("PROXY_MANAGEMENT_USERNAME", EnvType.string(), "");
        PROXY_MANAGEMENT_PASSWORD = builder.optional("PROXY_MANAGEMENT_PASSWORD", EnvType.string(), "");
        PROXY_MANAGEMENT_SESSION_SECRET = builder.optional("PROXY_MANAGEMENT_SESSION_SECRET", EnvType.string(), "");
        PROXY_CORS_ALLOWED_ORIGINS = builder.optional("PROXY_CORS_ALLOWED_ORIGINS", EnvType.string(), "");
        PROXY_LOGGING_AUDIT_FILE = builder.optional("PROXY_LOGGING_AUDIT_FILE", EnvType.string(), "logs/audit.log");
        PROXY_SERVER_URL = builder.optional("PROXY_SERVER_URL", EnvType.string(), "");
        STREAM_ANALYSIS_ENDPOINT = builder.optional("STREAM_ANALYSIS_ENDPOINT", EnvType.string(), "");
        SERVER_PORT = builder.optional("SERVER_PORT", EnvType.string(), "5000");
        ENV = builder.build();
    }

    private JarvisProxyEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("server.port", env.get(SERVER_PORT));
        values.put("proxy.storage.provider", env.get(PROXY_STORAGE_PROVIDER));
        values.put("proxy.storage.max-file-size", env.get(PROXY_STORAGE_MAX_FILE_SIZE));
        values.put("proxy.s3.bucket-name", env.get(PROXY_S3_BUCKET_NAME));
        values.put("proxy.s3.region", env.get(PROXY_S3_REGION));
        values.put("proxy.s3.endpoint", env.get(PROXY_S3_ENDPOINT));
        values.put("proxy.s3.path-style-access", env.get(PROXY_S3_PATH_STYLE_ACCESS));
        values.put("proxy.s3.server-side-encryption", env.get(PROXY_S3_SSE));
        values.put("proxy.azure.account-name", env.get(AZURE_STORAGE_ACCOUNT));
        values.put("proxy.azure.container-name", env.get(AZURE_STORAGE_CONTAINER));
        values.put("proxy.azure.endpoint", env.get(AZURE_STORAGE_ENDPOINT));
        values.put("proxy.azure.connection-string", env.get(AZURE_STORAGE_CONNECTION_STRING));
        values.put("proxy.auth-server.base-url", env.get(PROXY_AUTH_SERVER_BASE_URL));
        values.put("proxy.auth-server.identity-provisioning-key", env.get(AUTH_IDENTITY_PROVISIONING_KEY));
        values.put("proxy.management.username", env.get(PROXY_MANAGEMENT_USERNAME));
        values.put("proxy.management.password", env.get(PROXY_MANAGEMENT_PASSWORD));
        values.put("proxy.management.session-secret", env.get(PROXY_MANAGEMENT_SESSION_SECRET));
        values.put("proxy.cors.allowed-origins", env.get(PROXY_CORS_ALLOWED_ORIGINS));
        values.put("proxy.logging.audit-file", env.get(PROXY_LOGGING_AUDIT_FILE));
        values.put("proxy.server.url", env.get(PROXY_SERVER_URL));
        return values;
    }
}
