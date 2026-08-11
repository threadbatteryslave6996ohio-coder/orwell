package dev.orwell.objectstorage.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.orwell.env.Env;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObjectStorageProxyEnvsTest {
    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = ObjectStorageProxyEnvs.ENV.schema().from(Map.ofEntries(
                Map.entry("SERVER_ADDRESS", "0.0.0.0"),
                Map.entry("SERVER_PORT", "5000"),
                Map.entry("AUTH_BASE_URL", "http://auth-server/auth"),
                Map.entry("OBJECT_STORAGE_PROVIDER", "aws"),
                Map.entry("OBJECT_STORAGE_MAX_FILE_SIZE", "5368709120"),
                Map.entry("OBJECT_STORAGE_S3_BUCKET_NAME", "bucket"),
                Map.entry("OBJECT_STORAGE_S3_REGION", "us-east-1"),
                Map.entry("OBJECT_STORAGE_S3_ENDPOINT", ""),
                Map.entry("OBJECT_STORAGE_S3_PATH_STYLE_ACCESS", "false"),
                Map.entry("AZURE_STORAGE_ACCOUNT", ""),
                Map.entry("AZURE_STORAGE_CONTAINER", ""),
                Map.entry("AZURE_STORAGE_ENDPOINT", ""),
                Map.entry("AZURE_STORAGE_CONNECTION_STRING", ""),
                Map.entry("AUTH_IDENTITY_PROVISIONING_KEY", "provisioning-key"),
                Map.entry("OBJECT_STORAGE_ADMIN_AUTH_BASE_URL", "http://admin-auth-server/auth"),
                Map.entry("OBJECT_STORAGE_CORS_ALLOWED_ORIGINS", ""),
                Map.entry("OBJECT_STORAGE_LOGGING_AUDIT_FILE", "/tmp/audit.log"),
                Map.entry("OBJECT_STORAGE_SERVER_URL", "http://localhost:5000/jarvis"),
                Map.entry("JARVIS_SERVER_ROUTE_PREFIX", "/jarvis")
        ));

        assertEquals(Map.ofEntries(
                Map.entry("server.address", "0.0.0.0"),
                Map.entry("server.port", 5000),
                Map.entry("orwell.auth.base-url", "http://auth-server/auth"),
                Map.entry("object-storage.storage.provider", "aws"),
                Map.entry("object-storage.storage.max-file-size", 5368709120L),
                Map.entry("object-storage.s3.bucket-name", "bucket"),
                Map.entry("object-storage.s3.region", "us-east-1"),
                Map.entry("object-storage.s3.endpoint", ""),
                Map.entry("object-storage.s3.path-style-access", false),
                Map.entry("object-storage.azure.account-name", ""),
                Map.entry("object-storage.azure.container-name", ""),
                Map.entry("object-storage.azure.endpoint", ""),
                Map.entry("object-storage.azure.connection-string", ""),
                Map.entry("object-storage.auth-server.base-url", "http://auth-server/auth"),
                Map.entry("object-storage.auth-server.identity-provisioning-key", "provisioning-key"),
                Map.entry("object-storage.admin-auth.base-url", "http://admin-auth-server/auth"),
                Map.entry("object-storage.cors.allowed-origins", ""),
                Map.entry("object-storage.logging.audit-file", "/tmp/audit.log"),
                Map.entry("object-storage.server.url", "http://localhost:5000/jarvis"),
                Map.entry("jarvis.server.route-prefix", "/jarvis")
        ), ObjectStorageProxyEnvs.ENV.springProperties(env));
    }
}
