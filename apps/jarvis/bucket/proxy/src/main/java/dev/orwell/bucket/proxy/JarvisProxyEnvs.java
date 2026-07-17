package dev.orwell.bucket.proxy;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class JarvisProxyEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);
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
    public static final EnvOption<String> AUTH_IDENTITY_PROVISIONING_KEY;
    public static final EnvOption<String> PROXY_MANAGEMENT_USERNAME;
    public static final EnvOption<String> PROXY_MANAGEMENT_PASSWORD;
    public static final EnvOption<String> PROXY_MANAGEMENT_SESSION_SECRET;
    public static final EnvOption<String> PROXY_CORS_ALLOWED_ORIGINS;
    public static final EnvOption<String> PROXY_LOGGING_AUDIT_FILE;
    public static final EnvOption<String> PROXY_SERVER_URL;
    public static final EnvOption<String> STREAM_ANALYSIS_ENDPOINT;
    public static final EnvOption<String> JARVIS_SERVER_ROUTE_PREFIX;

    static {
        PROXY_STORAGE_PROVIDER = ENV.optional("PROXY_STORAGE_PROVIDER", EnvType.string(), "aws");
        PROXY_STORAGE_MAX_FILE_SIZE = ENV.optional("PROXY_STORAGE_MAX_FILE_SIZE", EnvType.longInteger(), 5368709120L);
        PROXY_S3_BUCKET_NAME = ENV.optional("PROXY_S3_BUCKET_NAME", EnvType.string(), "your-bucket-name");
        PROXY_S3_REGION = ENV.optional("PROXY_S3_REGION", EnvType.string(), "us-east-1");
        PROXY_S3_ENDPOINT = ENV.optional("PROXY_S3_ENDPOINT", EnvType.string(), "");
        PROXY_S3_PATH_STYLE_ACCESS = ENV.optional("PROXY_S3_PATH_STYLE_ACCESS", EnvType.bool(), false);
        PROXY_S3_SSE = ENV.optional("PROXY_S3_SSE", EnvType.string(), "AES256");
        AZURE_STORAGE_ACCOUNT = ENV.optional("AZURE_STORAGE_ACCOUNT", EnvType.string(), "");
        AZURE_STORAGE_CONTAINER = ENV.optional("AZURE_STORAGE_CONTAINER", EnvType.string(), "");
        AZURE_STORAGE_ENDPOINT = ENV.optional("AZURE_STORAGE_ENDPOINT", EnvType.string(), "");
        AZURE_STORAGE_CONNECTION_STRING = ENV.optional("AZURE_STORAGE_CONNECTION_STRING", EnvType.string(), "");
        AUTH_IDENTITY_PROVISIONING_KEY = ENV.optional("AUTH_IDENTITY_PROVISIONING_KEY", EnvType.string(), "");
        PROXY_MANAGEMENT_USERNAME = ENV.optional("PROXY_MANAGEMENT_USERNAME", EnvType.string(), "");
        PROXY_MANAGEMENT_PASSWORD = ENV.optional("PROXY_MANAGEMENT_PASSWORD", EnvType.string(), "");
        PROXY_MANAGEMENT_SESSION_SECRET = ENV.optional("PROXY_MANAGEMENT_SESSION_SECRET", EnvType.string(), "");
        PROXY_CORS_ALLOWED_ORIGINS = ENV.optional("PROXY_CORS_ALLOWED_ORIGINS", EnvType.string(), "");
        PROXY_LOGGING_AUDIT_FILE = ENV.optional("PROXY_LOGGING_AUDIT_FILE", EnvType.string(), "logs/audit.log");
        PROXY_SERVER_URL = ENV.optional("PROXY_SERVER_URL", EnvType.string(), "");
        STREAM_ANALYSIS_ENDPOINT = ENV.optional("STREAM_ANALYSIS_ENDPOINT", EnvType.string(), "");
        JARVIS_SERVER_ROUTE_PREFIX = ENV.optional("JARVIS_SERVER_ROUTE_PREFIX", EnvType.string(), "");
        ENV.property("proxy.storage.provider", PROXY_STORAGE_PROVIDER);
        ENV.property("proxy.storage.max-file-size", PROXY_STORAGE_MAX_FILE_SIZE);
        ENV.property("proxy.s3.bucket-name", PROXY_S3_BUCKET_NAME);
        ENV.property("proxy.s3.region", PROXY_S3_REGION);
        ENV.property("proxy.s3.endpoint", PROXY_S3_ENDPOINT);
        ENV.property("proxy.s3.path-style-access", PROXY_S3_PATH_STYLE_ACCESS);
        ENV.property("proxy.s3.server-side-encryption", PROXY_S3_SSE);
        ENV.property("proxy.azure.account-name", AZURE_STORAGE_ACCOUNT);
        ENV.property("proxy.azure.container-name", AZURE_STORAGE_CONTAINER);
        ENV.property("proxy.azure.endpoint", AZURE_STORAGE_ENDPOINT);
        ENV.property("proxy.azure.connection-string", AZURE_STORAGE_CONNECTION_STRING);
        ENV.property("proxy.auth-server.base-url", ENV.AUTH_BASE_URL);
        ENV.property("proxy.auth-server.identity-provisioning-key", AUTH_IDENTITY_PROVISIONING_KEY);
        ENV.property("proxy.management.username", PROXY_MANAGEMENT_USERNAME);
        ENV.property("proxy.management.password", PROXY_MANAGEMENT_PASSWORD);
        ENV.property("proxy.management.session-secret", PROXY_MANAGEMENT_SESSION_SECRET);
        ENV.property("proxy.cors.allowed-origins", PROXY_CORS_ALLOWED_ORIGINS);
        ENV.property("proxy.logging.audit-file", PROXY_LOGGING_AUDIT_FILE);
        ENV.property("proxy.server.url", PROXY_SERVER_URL);
        ENV.property("jarvis.server.route-prefix", JARVIS_SERVER_ROUTE_PREFIX);
    }

    private JarvisProxyEnvs() {
    }
}
