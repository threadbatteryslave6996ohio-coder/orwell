package dev.orwell.objectstorage.proxy;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class ObjectStorageProxyEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, true);
    public static final EnvOption<String> OBJECT_STORAGE_PROVIDER;
    public static final EnvOption<Long> OBJECT_STORAGE_MAX_FILE_SIZE;
    public static final EnvOption<String> OBJECT_STORAGE_S3_BUCKET_NAME;
    public static final EnvOption<String> OBJECT_STORAGE_S3_REGION;
    public static final EnvOption<String> OBJECT_STORAGE_S3_ENDPOINT;
    public static final EnvOption<Boolean> OBJECT_STORAGE_S3_PATH_STYLE_ACCESS;
    public static final EnvOption<String> AZURE_STORAGE_ACCOUNT;
    public static final EnvOption<String> AZURE_STORAGE_CONTAINER;
    public static final EnvOption<String> AZURE_STORAGE_ENDPOINT;
    public static final EnvOption<String> AZURE_STORAGE_CONNECTION_STRING;
    public static final EnvOption<String> AUTH_IDENTITY_PROVISIONING_KEY;
    public static final EnvOption<String> OBJECT_STORAGE_MANAGEMENT_USERNAME;
    public static final EnvOption<String> OBJECT_STORAGE_MANAGEMENT_PASSWORD;
    public static final EnvOption<String> OBJECT_STORAGE_MANAGEMENT_SESSION_SECRET;
    public static final EnvOption<String> OBJECT_STORAGE_CORS_ALLOWED_ORIGINS;
    public static final EnvOption<String> OBJECT_STORAGE_LOGGING_AUDIT_FILE;
    public static final EnvOption<String> OBJECT_STORAGE_SERVER_URL;
    public static final EnvOption<String> STREAM_ANALYSIS_ENDPOINT;
    public static final EnvOption<String> JARVIS_SERVER_ROUTE_PREFIX;

    static {
        OBJECT_STORAGE_PROVIDER = ENV.optional("OBJECT_STORAGE_PROVIDER", EnvType.string(), "aws");
        OBJECT_STORAGE_MAX_FILE_SIZE = ENV.optional("OBJECT_STORAGE_MAX_FILE_SIZE", EnvType.longInteger(), 5368709120L);
        OBJECT_STORAGE_S3_BUCKET_NAME = ENV.optional("OBJECT_STORAGE_S3_BUCKET_NAME", EnvType.string(), "your-bucket-name");
        OBJECT_STORAGE_S3_REGION = ENV.optional("OBJECT_STORAGE_S3_REGION", EnvType.string(), "us-east-1");
        OBJECT_STORAGE_S3_ENDPOINT = ENV.optional("OBJECT_STORAGE_S3_ENDPOINT", EnvType.string(), "");
        OBJECT_STORAGE_S3_PATH_STYLE_ACCESS = ENV.optional("OBJECT_STORAGE_S3_PATH_STYLE_ACCESS", EnvType.bool(), false);
        AZURE_STORAGE_ACCOUNT = ENV.optional("AZURE_STORAGE_ACCOUNT", EnvType.string(), "");
        AZURE_STORAGE_CONTAINER = ENV.optional("AZURE_STORAGE_CONTAINER", EnvType.string(), "");
        AZURE_STORAGE_ENDPOINT = ENV.optional("AZURE_STORAGE_ENDPOINT", EnvType.string(), "");
        AZURE_STORAGE_CONNECTION_STRING = ENV.optional("AZURE_STORAGE_CONNECTION_STRING", EnvType.string(), "");
        AUTH_IDENTITY_PROVISIONING_KEY = ENV.optional("AUTH_IDENTITY_PROVISIONING_KEY", EnvType.string(), "");
        OBJECT_STORAGE_MANAGEMENT_USERNAME = ENV.optional("OBJECT_STORAGE_MANAGEMENT_USERNAME", EnvType.string(), "");
        OBJECT_STORAGE_MANAGEMENT_PASSWORD = ENV.optional("OBJECT_STORAGE_MANAGEMENT_PASSWORD", EnvType.string(), "");
        OBJECT_STORAGE_MANAGEMENT_SESSION_SECRET = ENV.optional("OBJECT_STORAGE_MANAGEMENT_SESSION_SECRET", EnvType.string(), "");
        OBJECT_STORAGE_CORS_ALLOWED_ORIGINS = ENV.optional("OBJECT_STORAGE_CORS_ALLOWED_ORIGINS", EnvType.string(), "");
        OBJECT_STORAGE_LOGGING_AUDIT_FILE = ENV.optional("OBJECT_STORAGE_LOGGING_AUDIT_FILE", EnvType.string(), "logs/audit.log");
        OBJECT_STORAGE_SERVER_URL = ENV.optional("OBJECT_STORAGE_SERVER_URL", EnvType.string(), "");
        STREAM_ANALYSIS_ENDPOINT = ENV.optional("STREAM_ANALYSIS_ENDPOINT", EnvType.string(), "");
        JARVIS_SERVER_ROUTE_PREFIX = ENV.optional("JARVIS_SERVER_ROUTE_PREFIX", EnvType.string(), "");
        ENV.property("object-storage.storage.provider", OBJECT_STORAGE_PROVIDER);
        ENV.property("object-storage.storage.max-file-size", OBJECT_STORAGE_MAX_FILE_SIZE);
        ENV.property("object-storage.s3.bucket-name", OBJECT_STORAGE_S3_BUCKET_NAME);
        ENV.property("object-storage.s3.region", OBJECT_STORAGE_S3_REGION);
        ENV.property("object-storage.s3.endpoint", OBJECT_STORAGE_S3_ENDPOINT);
        ENV.property("object-storage.s3.path-style-access", OBJECT_STORAGE_S3_PATH_STYLE_ACCESS);
        ENV.property("object-storage.azure.account-name", AZURE_STORAGE_ACCOUNT);
        ENV.property("object-storage.azure.container-name", AZURE_STORAGE_CONTAINER);
        ENV.property("object-storage.azure.endpoint", AZURE_STORAGE_ENDPOINT);
        ENV.property("object-storage.azure.connection-string", AZURE_STORAGE_CONNECTION_STRING);
        ENV.property("object-storage.auth-server.base-url", ENV.AUTH_BASE_URL);
        ENV.property("object-storage.auth-server.identity-provisioning-key", AUTH_IDENTITY_PROVISIONING_KEY);
        ENV.property("object-storage.management.username", OBJECT_STORAGE_MANAGEMENT_USERNAME);
        ENV.property("object-storage.management.password", OBJECT_STORAGE_MANAGEMENT_PASSWORD);
        ENV.property("object-storage.management.session-secret", OBJECT_STORAGE_MANAGEMENT_SESSION_SECRET);
        ENV.property("object-storage.cors.allowed-origins", OBJECT_STORAGE_CORS_ALLOWED_ORIGINS);
        ENV.property("object-storage.logging.audit-file", OBJECT_STORAGE_LOGGING_AUDIT_FILE);
        ENV.property("object-storage.server.url", OBJECT_STORAGE_SERVER_URL);
        ENV.property("jarvis.server.route-prefix", JARVIS_SERVER_ROUTE_PREFIX);
    }

    private ObjectStorageProxyEnvs() {
    }
}
