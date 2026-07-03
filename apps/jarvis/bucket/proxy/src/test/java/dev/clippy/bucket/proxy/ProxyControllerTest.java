package dev.clippy.bucket.proxy;

import dev.clippy.bucket.proxy.storage.BucketStorage;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyControllerTest {
    @Test
    void loginPropagatesUpstreamUnauthorizedStatus() {
        ProxyController controller = newController(
                new StubAuthServerClient(loginResult(false, HttpStatus.UNAUTHORIZED.value(), null, null)),
                new StubS3Service(),
                properties("http://localhost")
        );

        ResponseEntity<Map<String, Object>> response = controller.login(new ProxyController.ProxyLoginRequest("client", "secret"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).containsEntry("error", "Invalid username or password");
    }

    @Test
    void createIdentityPropagatesConflictStatus() {
        ProxyController controller = newController(
                new StubAuthServerClient(identityResult(false, HttpStatus.CONFLICT.value(), "client", null)),
                new StubS3Service(),
                properties("http://localhost")
        );
        String sessionToken = new ManagementSessionService(properties("http://localhost")).createSession("admin", Instant.now().plusSeconds(3600));

        ResponseEntity<String> response = controller.createIdentity(
                sessionToken,
                "new-client",
                "secret"
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("HTTP 409");
    }

    @Test
    void badRequestHandlerMapsIllegalArgumentExceptionsToHttp400() {
        ProxyController controller = newController(
                new StubAuthServerClient(loginResult(true, HttpStatus.OK.value(), "client", "token")),
                new StubS3Service(),
                properties("http://localhost")
        );

        ResponseEntity<Map<String, Object>> response = controller.badRequest(new IllegalArgumentException("Invalid object key."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("error", "Invalid object key.");
    }

    @Test
    void adminLoginUsesSecureCookieWhenServerUrlIsHttps() {
        ProxyController controller = newController(
                new StubAuthServerClient(loginResult(true, HttpStatus.OK.value(), "client", "token")),
                new StubS3Service(),
                properties("https://proxy.example.com")
        );
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<String> response = controller.adminLogin("admin", "password", servletResponse);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(servletResponse.getHeader(HttpHeaders.SET_COOKIE)).contains("Secure");
    }

    private static ProxyController newController(AuthServerClient authServerClient, BucketStorage storage, ProxyProperties properties) {
        return new ProxyController(properties, authServerClient, storage, newFileAuditLogger(properties), new ManagementSessionService(properties));
    }

    private static ProxyProperties properties(String serverUrl) {
        try {
            String auditFile = Files.createTempFile("proxy-audit-", ".log").toString();
            return new ProxyProperties(
                    new ProxyProperties.Storage("aws", 5L * 1024 * 1024 * 1024),
                    new ProxyProperties.S3("bucket", "us-east-1", null, false, "AES256"),
                    new ProxyProperties.Azure("account", "container", null, null),
                    new ProxyProperties.AuthServer("http://localhost:8081", "provisioning-key"),
                    new ProxyProperties.Management("admin", "password", "session-secret"),
                    new ProxyProperties.Cors(List.of()),
                    new ProxyProperties.Server(serverUrl),
                    new ProxyProperties.Logging(auditFile)
            );
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static FileAuditLogger newFileAuditLogger(ProxyProperties properties) {
        try {
            return new FileAuditLogger(properties);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AuthServerClient.AuthCallResult loginResult(boolean success, int statusCode, String clientId, String token) {
        return new AuthServerClient.AuthCallResult(success, statusCode, clientId, token);
    }

    private static AuthServerClient.AuthCallResult identityResult(boolean success, int statusCode, String clientId, String token) {
        return new AuthServerClient.AuthCallResult(success, statusCode, clientId, token);
    }

    private static final class StubAuthServerClient extends AuthServerClient {
        private final AuthServerClient.AuthCallResult loginResult;
        private final AuthServerClient.AuthCallResult identityResult;

        private StubAuthServerClient(AuthServerClient.AuthCallResult result) {
            super(new dev.clippy.auth.client.ClippyAuthClient("http://localhost:8081"),
                    org.springframework.web.client.RestClient.builder().baseUrl("http://localhost:8081").build(),
                    "provisioning-key",
                    newFileAuditLogger(properties("http://localhost")));
            this.loginResult = result;
            this.identityResult = result;
        }

        @Override
        public AuthServerClient.AuthCallResult login(String clientId, String secret) {
            return loginResult;
        }

        @Override
        public boolean isTokenValidForClient(String clientId, String token) {
            return true;
        }

        @Override
        public AuthServerClient.AuthCallResult createIdentity(String clientId, String secret) {
            return identityResult;
        }
    }

    private static class StubS3Service implements BucketStorage {
        @Override
        public UploadResult upload(Path file, String contentType, String folder, String fileName) {
            return new UploadResult(folder + "/" + fileName, "etag");
        }

        @Override
        public List<StoredObject> list(String folder) {
            return List.of();
        }

        @Override
        public ObjectMetadata metadata(String key) {
            return new ObjectMetadata(true, 1L, "etag", Instant.now());
        }

        @Override
        public void delete(String key) {
        }

        @Override
        public String provider() {
            return "test";
        }

        @Override
        public String containerName() {
            return "bucket";
        }

        @Override
        public String location() {
            return "test-location";
        }
    }
}
