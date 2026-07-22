package dev.orwell.bucket.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.orwell.env.Env;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JarvisProxyEnvsTest {
    @Test
    void exposesResolvedValuesAsSpringApplicationProperties() {
        Env env = JarvisProxyEnvs.ENV.schema().from(Map.ofEntries(
                Map.entry("SERVER_ADDRESS", "0.0.0.0"),
                Map.entry("SERVER_PORT", "5000"),
                Map.entry("AUTH_BASE_URL", "http://auth-server/auth"),
                Map.entry("PROXY_STORAGE_PROVIDER", "aws"),
                Map.entry("PROXY_STORAGE_MAX_FILE_SIZE", "5368709120"),
                Map.entry("PROXY_S3_BUCKET_NAME", "bucket"),
                Map.entry("PROXY_S3_REGION", "us-east-1"),
                Map.entry("PROXY_S3_ENDPOINT", ""),
                Map.entry("PROXY_S3_PATH_STYLE_ACCESS", "false"),
                Map.entry("AZURE_STORAGE_ACCOUNT", ""),
                Map.entry("AZURE_STORAGE_CONTAINER", ""),
                Map.entry("AZURE_STORAGE_ENDPOINT", ""),
                Map.entry("AZURE_STORAGE_CONNECTION_STRING", ""),
                Map.entry("AUTH_IDENTITY_PROVISIONING_KEY", "provisioning-key"),
                Map.entry("PROXY_MANAGEMENT_USERNAME", "admin"),
                Map.entry("PROXY_MANAGEMENT_PASSWORD", "password"),
                Map.entry("PROXY_MANAGEMENT_SESSION_SECRET", "session-secret"),
                Map.entry("PROXY_CORS_ALLOWED_ORIGINS", ""),
                Map.entry("PROXY_LOGGING_AUDIT_FILE", "/tmp/audit.log"),
                Map.entry("PROXY_SERVER_URL", "http://localhost:5000/jarvis"),
                Map.entry("JARVIS_SERVER_ROUTE_PREFIX", "/jarvis")
        ));

        assertEquals(Map.ofEntries(
                Map.entry("server.address", "0.0.0.0"),
                Map.entry("server.port", 5000),
                Map.entry("orwell.auth.base-url", "http://auth-server/auth"),
                Map.entry("proxy.storage.provider", "aws"),
                Map.entry("proxy.storage.max-file-size", 5368709120L),
                Map.entry("proxy.s3.bucket-name", "bucket"),
                Map.entry("proxy.s3.region", "us-east-1"),
                Map.entry("proxy.s3.endpoint", ""),
                Map.entry("proxy.s3.path-style-access", false),
                Map.entry("proxy.azure.account-name", ""),
                Map.entry("proxy.azure.container-name", ""),
                Map.entry("proxy.azure.endpoint", ""),
                Map.entry("proxy.azure.connection-string", ""),
                Map.entry("proxy.auth-server.base-url", "http://auth-server/auth"),
                Map.entry("proxy.auth-server.identity-provisioning-key", "provisioning-key"),
                Map.entry("proxy.management.username", "admin"),
                Map.entry("proxy.management.password", "password"),
                Map.entry("proxy.management.session-secret", "session-secret"),
                Map.entry("proxy.cors.allowed-origins", ""),
                Map.entry("proxy.logging.audit-file", "/tmp/audit.log"),
                Map.entry("proxy.server.url", "http://localhost:5000/jarvis"),
                Map.entry("jarvis.server.route-prefix", "/jarvis")
        ), JarvisProxyEnvs.ENV.springProperties(env));
    }
}
