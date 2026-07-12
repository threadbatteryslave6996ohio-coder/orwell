package dev.orwell.combined;

import dev.orwell.auth.http.server.repository.ClientIdentityRepository;
import dev.orwell.auth.http.server.repository.ClientTokenRepository;
import dev.orwell.server.model.ClipboardEntry;
import dev.orwell.server.repository.ClipboardEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = CombinedServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {
                "clippy.auth.route-prefix=/auth",
                "clippy.server.route-prefix=/klippy",
                "jarvis.server.route-prefix=/jarvis",
                "keeboarder.server.route-prefix=/keeboarder",
                "proxy.storage.provider=aws",
                "proxy.s3.bucket-name=test-bucket",
                "proxy.s3.region=us-east-1",
                "proxy.s3.endpoint=http://localhost:1",
                "proxy.s3.path-style-access=true",
                "proxy.auth-server.base-url=http://localhost:1",
                "proxy.auth-server.identity-provisioning-key=test",
                "proxy.management.username=test",
                "proxy.management.password=test",
                "proxy.management.session-secret=01234567890123456789012345678901",
                "proxy.logging.audit-file=target/combined-audit.log",
                "clippy.auth.jpa.hibernate.ddl-auto=create-drop",
                "clippy.clipboard.jpa.hibernate.ddl-auto=create-drop",
                "clippy.jpa.jdbc-time-zone=UTC",
                "secrets.jpa.hibernate.ddl-auto=create-drop",
                "secrets.route-prefix=/secrets"
        }
)
class CombinedServerHttpIntegrationTest {
    static final int SERVER_PORT = reservePort();
    static final int WEBSOCKET_PORT = reservePort();

    @Container
    static final PostgreSQLContainer<?> authPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer<?> clipboardPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final PostgreSQLContainer<?> secretsPostgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> SERVER_PORT);
        registry.add("keeboarder.websocket.port", () -> WEBSOCKET_PORT);
        registry.add("clippy.auth.base-url", () -> "http://localhost:" + SERVER_PORT + "/auth");
        registry.add("clippy.auth.datasource.url", authPostgres::getJdbcUrl);
        registry.add("clippy.auth.datasource.username", authPostgres::getUsername);
        registry.add("clippy.auth.datasource.password", authPostgres::getPassword);
        registry.add("spring.datasource.url", clipboardPostgres::getJdbcUrl);
        registry.add("spring.datasource.username", clipboardPostgres::getUsername);
        registry.add("spring.datasource.password", clipboardPostgres::getPassword);
        registry.add("secrets.datasource.url", secretsPostgres::getJdbcUrl);
        registry.add("secrets.datasource.username", secretsPostgres::getUsername);
        registry.add("secrets.datasource.password", secretsPostgres::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ClientIdentityRepository identityRepository;

    @Autowired
    private ClientTokenRepository tokenRepository;

    @Autowired
    private ClipboardEntryRepository clipboardEntryRepository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearDatabases() {
        clipboardEntryRepository.deleteAll();
        tokenRepository.deleteAll();
        identityRepository.deleteAll();
    }

    @Test
    void combinedServerCreatesIdentityLogsInAndStoresClipboardEntry() throws Exception {
        HttpResponse<String> createIdentity = post(
                "/auth/identities",
                """
                        {
                          "clientId": "android-pixel-8",
                          "secret": "change-me-please"
                        }
                        """,
                null
        );

        HttpResponse<String> login = post(
                "/auth/login",
                """
                        {
                          "clientId": "android-pixel-8",
                          "secret": "change-me-please"
                        }
                        """,
                null
        );

        String token = jsonValue(login.body(), "token");
        HttpResponse<String> clipboardWrite = post(
                "/klippy/clipboard",
                """
                        {
                          "clientId": "android-pixel-8",
                          "content": "clipboard text",
                          "timestamp": "2026-06-23T12:00:00Z"
                        }
                        """,
                "android-pixel-8",
                token
        );

        assertThat(createIdentity.statusCode()).isEqualTo(201);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(token).isNotBlank();
        assertThat(clipboardWrite.statusCode()).isEqualTo(201);
        assertThat(identityRepository.count()).isEqualTo(1);
        assertThat(clipboardEntryRepository.count()).isEqualTo(1);
        ClipboardEntry saved = clipboardEntryRepository.findAll().getFirst();
        assertThat(saved.getClientId()).isEqualTo("android-pixel-8");
        assertThat(saved.getContent()).isEqualTo("clipboard text");
    }

    @Test
    void combinedServerRejectsClipboardWriteWhenTokenIsInvalid() throws Exception {
        post(
                "/auth/identities",
                """
                        {
                          "clientId": "android-pixel-8",
                          "secret": "change-me-please"
                        }
                        """,
                null
        );

        HttpResponse<String> response = post(
                "/klippy/clipboard",
                """
                        {
                          "clientId": "android-pixel-8",
                          "content": "clipboard text",
                          "timestamp": "2026-06-23T12:00:00Z"
                        }
                        """,
                "invalid-token"
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(clipboardEntryRepository.count()).isZero();
    }

    @Test
    void registersJarvisAndKeeboarderAsSubAppEndpoints() throws Exception {
        post(
                "/auth/identities",
                """
                        {"clientId":"keeboarder-admin","secret":"change-me-please"}
                        """,
                null
        );
        HttpResponse<String> login = post(
                "/auth/login",
                """
                        {"clientId":"keeboarder-admin","secret":"change-me-please"}
                        """,
                null
        );
        String token = jsonValue(login.body(), "token");
        HttpResponse<String> registry = get("/");
        HttpResponse<String> jarvis = get("/jarvis/health");
        HttpResponse<String> keeboarder = get("/keeboarder/clients", "keeboarder-admin", token);

        assertThat(registry.statusCode()).isEqualTo(200);
        assertThat(registry.body()).contains("auth", "klippy", "jarvis", "keeboarder", "secrets");
        assertThat(jarvis.statusCode()).isEqualTo(200);
        assertThat(keeboarder.statusCode()).isEqualTo(200);
        assertThat(keeboarder.body()).isEqualTo("[]");
    }

    private HttpResponse<String> post(String path, String json, String bearerToken) throws IOException, InterruptedException {
        return post(path, json, null, bearerToken);
    }

    private HttpResponse<String> post(String path, String json, String clientId, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (clientId != null) {
            builder.header("X-Client-Id", clientId);
        }
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:%d%s".formatted(port, path)))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String clientId, String bearerToken)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("X-Client-Id", clientId)
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String jsonValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing JSON key: " + key);
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return json.substring(valueStart, valueEnd);
    }

    private static int reservePort() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
                return socket.getLocalPort();
            } catch (IOException ignored) {
                // Retry with another ephemeral port.
            }
        }
        throw new IllegalStateException("No port is available after 3 attempts.");
    }
}
