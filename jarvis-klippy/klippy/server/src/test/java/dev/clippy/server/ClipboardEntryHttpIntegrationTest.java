package dev.clippy.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ClipboardEntryHttpIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ClipboardEntryRepository repository;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void clearDatabase() {
        repository.deleteAll();
    }

    @Test
    void createsClipboardEntryFromHttpRequestAndPersistsItInPostgres() throws Exception {
        Instant timestamp = Instant.parse("2026-06-23T12:00:00Z");
        String json = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text",
                  "timestamp": "%s"
                }
                """.formatted(timestamp);

        HttpResponse<String> response = post("/clipboard", json, "valid-token");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"id\":");
        assertThat(response.body()).contains("\"clientId\":\"android-pixel-8\"");
        assertThat(response.body()).contains("\"timestamp\":\"" + timestamp + "\"");

        List<ClipboardEntry> entries = repository.findAll();
        assertThat(entries).hasSize(1);

        ClipboardEntry saved = entries.get(0);
        assertThat(saved.getClientId()).isEqualTo("android-pixel-8");
        assertThat(saved.getContent()).isEqualTo("clipboard text");
        assertThat(saved.getTimestamp()).isEqualTo(timestamp);
    }

    @Test
    void rejectsClipboardEntryWithoutBearerToken() throws Exception {
        String json = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text"
                }
                """;

        HttpResponse<String> response = post("/clipboard", json, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    void persistsSameContentAtDifferentTimestamps() throws Exception {
        String first = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text",
                  "timestamp": "2026-06-23T12:00:00Z"
                }
                """;
        String duplicate = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text",
                  "timestamp": "2026-06-23T12:01:00Z"
                }
                """;

        HttpResponse<String> firstResponse = post("/clipboard", first, "valid-token");
        HttpResponse<String> duplicateResponse = post("/clipboard", duplicate, "valid-token");

        assertThat(firstResponse.statusCode()).isEqualTo(201);
        assertThat(duplicateResponse.statusCode()).isEqualTo(201);
        assertThat(duplicateResponse.body()).isNotEqualTo(firstResponse.body());
        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void persistsOlderBackfillWhenLatestContentMatches() throws Exception {
        repository.save(new ClipboardEntry(
                "android-pixel-8", "clipboard text", Instant.parse("2026-06-23T12:01:00Z")));
        String historical = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text",
                  "timestamp": "2026-06-23T12:00:00Z"
                }
                """;

        HttpResponse<String> firstResponse = post("/clipboard", historical, "valid-token");
        HttpResponse<String> retryResponse = post("/clipboard", historical, "valid-token");

        assertThat(firstResponse.statusCode()).isEqualTo(201);
        assertThat(retryResponse.statusCode()).isEqualTo(201);
        assertThat(retryResponse.body()).isEqualTo(firstResponse.body());
        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    void deduplicatesNanosecondTimestampAfterDatabasePrecisionNormalization() throws Exception {
        String entry = """
                {
                  "clientId": "android-pixel-8",
                  "content": "clipboard text",
                  "timestamp": "2026-06-23T12:00:00.123456789Z"
                }
                """;

        HttpResponse<String> firstResponse = post("/clipboard", entry, "valid-token");
        HttpResponse<String> retryResponse = post("/clipboard", entry, "valid-token");

        assertThat(firstResponse.statusCode()).isEqualTo(201);
        assertThat(retryResponse.body()).isEqualTo(firstResponse.body());
        assertThat(repository.findAll()).hasSize(1);
        assertThat(repository.findAll().getFirst().getTimestamp())
                .isEqualTo(Instant.parse("2026-06-23T12:00:00.123456Z"));
    }

    @Test
    void returnsAuthenticatedClientEntriesWithinInclusiveTimeframe() throws Exception {
        repository.save(new ClipboardEntry("android-pixel-8", "before", Instant.parse("2026-06-23T11:59:59Z")));
        repository.save(new ClipboardEntry("android-pixel-8", "from", Instant.parse("2026-06-23T12:00:00Z")));
        repository.save(new ClipboardEntry("android-pixel-8", "to", Instant.parse("2026-06-23T13:00:00Z")));
        repository.save(new ClipboardEntry("other-client", "private", Instant.parse("2026-06-23T12:30:00Z")));

        HttpResponse<String> response = get(
                "/clipboard?clientId=android-pixel-8&from=2026-06-23T12%3A00%3A00Z&to=2026-06-23T13%3A00%3A00Z",
                "valid-token"
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"content\":\"from\"");
        assertThat(response.body()).contains("\"content\":\"to\"");
        assertThat(response.body()).doesNotContain("before").doesNotContain("private");
    }

    @Test
    void rejectsTimeframeQueryWithoutBearerToken() throws Exception {
        repository.save(new ClipboardEntry(
                "android-pixel-8", "private", Instant.parse("2026-06-23T12:00:00Z")));

        HttpResponse<String> response = get(
                "/clipboard?clientId=android-pixel-8&from=2026-06-23T12%3A00%3A00Z"
                        + "&to=2026-06-23T13%3A00%3A00Z",
                null
        );

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain("private");
    }

    @Test
    void pagesTimeframeEntriesWithTimestampAndIdCursor() throws Exception {
        Instant timestamp = Instant.parse("2026-06-23T12:00:00Z");
        ClipboardEntry first = repository.save(new ClipboardEntry("android-pixel-8", "first", timestamp));
        ClipboardEntry second = repository.save(new ClipboardEntry("android-pixel-8", "second", timestamp));
        repository.save(new ClipboardEntry(
                "android-pixel-8", "third", Instant.parse("2026-06-23T12:01:00Z")));

        HttpResponse<String> firstPage = get(
                "/clipboard?clientId=android-pixel-8&from=2026-06-23T12%3A00%3A00Z"
                        + "&to=2026-06-23T13%3A00%3A00Z&limit=2",
                "valid-token"
        );
        HttpResponse<String> secondPage = get(
                "/clipboard?clientId=android-pixel-8&from=2026-06-23T12%3A00%3A00Z"
                        + "&to=2026-06-23T13%3A00%3A00Z&limit=2"
                        + "&afterTimestamp=2026-06-23T12%3A00%3A00Z&afterId=" + second.getId(),
                "valid-token"
        );

        assertThat(firstPage.statusCode()).isEqualTo(200);
        assertThat(firstPage.body()).contains("\"id\":" + first.getId(), "\"id\":" + second.getId())
                .doesNotContain("third");
        assertThat(secondPage.statusCode()).isEqualTo(200);
        assertThat(secondPage.body()).contains("third").doesNotContain("first").doesNotContain("second");
    }

    private HttpResponse<String> post(String path, String json, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:%d%s".formatted(port, path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String bearerToken) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(
                        URI.create("http://localhost:%d%s".formatted(port, path)))
                .GET();
        if (bearerToken != null) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = builder.build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AuthTokenVerifier authTokenVerifier() {
            return (clientId, token) -> "android-pixel-8".equals(clientId) && "valid-token".equals(token);
        }
    }
}
