package dev.orwell.http;

import dev.orwell.testing.http.JsonStubServer;
import dev.orwell.logging.LogEntry;
import dev.orwell.logging.LogLevel;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercised against a real server on a loopback port, like its sibling: the status and the
 * duration in a record only mean something if a real request produced them.
 */
class LoggingHttpClientTest {
    private final List<LogEntry> records = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = records::add;

    private JsonStubServer server;
    private URI url;

    @BeforeEach
    void startServer() throws IOException {
        server = JsonStubServer.start();
        server.on("/thing", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.on("/broken", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        url = URI.create(server.baseUrl() + "/thing");
    }

    @AfterEach
    void stopServer() {
        server.close();
    }

    private URI path(String path) {
        return URI.create(server.baseUrl() + path);
    }

    private LogEntry onlyRecord() {
        assertEquals(1, records.size(), () -> "expected one record, got " + records);
        return records.getFirst();
    }

    /**
     * The async record is attached to the delegate's own future, so it races the caller's
     * continuation by design — a test that reads {@code records} the instant {@code get()} returns
     * is asserting an ordering the wrapper deliberately does not promise.
     */
    private void awaitRecords(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (records.size() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertEquals(expected, records.size(), () -> "expected " + expected + " records, got " + records);
    }

    @Test
    void aCompletedRequestIsRecordedWithItsMethodTargetStatusAndDuration() throws Exception {
        HttpClient client = LoggingHttpClient.wrap(HttpClient.newHttpClient(), logger);

        client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding());

        LogEntry record = onlyRecord();
        assertEquals(LogLevel.INFO, record.level());
        assertEquals("Sent HTTP request.", record.message());
        assertEquals("GET", record.metadata().get("method"));
        assertEquals(url.toString(), record.metadata().get("uri"));
        assertEquals(204, record.metadata().get("status"));
        assertTrue((Long) record.metadata().get("durationMs") >= 0);
    }

    @Test
    void anErrorStatusIsAResponseLikeAnyOther() throws Exception {
        HttpClient client = LoggingHttpClient.wrap(HttpClient.newHttpClient(), logger);

        client.send(HttpRequest.newBuilder(path("/broken")).build(), HttpResponse.BodyHandlers.discarding());

        // 503 is the server answering, not the request failing — the level says so, the status
        // carries the news, and an app that treats it as fatal logs that itself.
        assertEquals(LogLevel.INFO, onlyRecord().level());
        assertEquals(503, onlyRecord().metadata().get("status"));
    }

    @Test
    void theQueryStringIsLeftOutBecauseTokensTravelInIt() throws Exception {
        HttpClient client = LoggingHttpClient.wrap(HttpClient.newHttpClient(), logger);

        client.send(HttpRequest.newBuilder(path("/thing?token=secret&from=12")).build(),
                HttpResponse.BodyHandlers.discarding());

        String logged = String.valueOf(onlyRecord().metadata().get("uri"));
        assertEquals(url.toString(), logged);
        assertFalse(logged.contains("secret"));
    }

    @Test
    void aRequestThatNeverReachedAServerIsRecordedAsAWarningAndRethrown() {
        HttpClient client = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(), logger);
        // Port 1 on loopback: refused immediately, so this is the failure path, not a slow test.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/thing")).build();

        assertThrows(IOException.class,
                () -> client.send(request, HttpResponse.BodyHandlers.discarding()));

        LogEntry record = onlyRecord();
        assertEquals(LogLevel.WARN, record.level());
        assertEquals("HTTP request failed.", record.message());
        assertNotNull(record.metadata().get("error"));
        assertFalse(record.metadata().containsKey("status"));
    }

    @Test
    void theAsyncPathsRecordToo() throws Exception {
        HttpClient client = LoggingHttpClient.wrap(HttpClient.newHttpClient(), logger);

        client.sendAsync(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding()).get();
        client.sendAsync(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding(), null).get();

        awaitRecords(2);
        assertTrue(records.stream().allMatch(record -> record.metadata().get("status").equals(204)));
    }

    @Test
    void anAsyncFailureIsRecordedWithTheCauseRatherThanItsCompletionWrapper() throws Exception {
        HttpClient client = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(), logger);

        assertThrows(ExecutionException.class, () -> client.sendAsync(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/thing")).build(),
                HttpResponse.BodyHandlers.discarding()).get());

        awaitRecords(1);
        LogEntry record = onlyRecord();
        assertEquals(LogLevel.WARN, record.level());
        assertFalse(String.valueOf(record.metadata().get("error")).contains("CompletionException"));
    }

    @Test
    void theFutureHandedBackIsTheOneTheDelegateReturned() {
        // Cancelling a future derived by whenComplete would not cancel the request behind it.
        RecordingClient recorder = new RecordingClient();
        HttpClient client = LoggingHttpClient.wrap(recorder, logger);

        assertSame(recorder.pending,
                client.sendAsync(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding()));
    }

    @Test
    void aQuieterLevelIsHonouredForSuccessesAndIgnoredForFailures() throws Exception {
        HttpClient client = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(200)).build(), logger, LogLevel.DEBUG);

        client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding());
        assertThrows(IOException.class, () -> client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:1/thing")).build(),
                HttpResponse.BodyHandlers.discarding()));

        assertEquals(List.of(LogLevel.DEBUG, LogLevel.WARN), records.stream().map(LogEntry::level).toList());
    }

    @Test
    void aSinkThatThrowsDoesNotBreakTheRequest() throws Exception {
        Logger broken = entry -> {
            throw new IllegalStateException("sink is down");
        };
        HttpClient client = LoggingHttpClient.wrap(HttpClient.newHttpClient(), broken);

        HttpResponse<Void> response =
                client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.discarding());

        assertEquals(204, response.statusCode());
    }

    @Test
    void itComposesWithTheServiceHeaderWrapper() throws Exception {
        List<String> receivedService = Collections.synchronizedList(new ArrayList<>());
        server.on("/attributed", exchange -> {
            receivedService.add(String.valueOf(exchange.getRequestHeaders().getFirst("Service")));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        HttpClient client = LoggingHttpClient.wrap(
                ServiceHeaderHttpClient.wrap(HttpClient.newHttpClient(), "secrets-manager-client"), logger);

        client.send(HttpRequest.newBuilder(path("/attributed")).build(), HttpResponse.BodyHandlers.discarding());

        assertEquals(List.of("secrets-manager-client"), receivedService);
        assertEquals(204, onlyRecord().metadata().get("status"));
    }

    @Test
    void theWrapperReportsTheDelegatesConfigurationAndClosesIt() throws Exception {
        HttpClient delegate = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(7))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        HttpClient client = LoggingHttpClient.wrap(delegate, logger);

        assertEquals(delegate.connectTimeout(), client.connectTimeout());
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertEquals(delegate.version(), client.version());
        assertEquals(delegate.executor().isPresent(), client.executor().isPresent());
        assertNotNull(client.sslContext());
        assertNotNull(client.sslParameters());
        assertTrue(client.proxy().isEmpty());
        assertTrue(client.authenticator().isEmpty());
        assertTrue(client.cookieHandler().isEmpty());
        assertEquals(LogLevel.INFO, ((LoggingHttpClient) client).level());

        client.close();

        assertTrue(delegate.isTerminated());
        assertTrue(client.isTerminated());
        assertTrue(client.awaitTermination(Duration.ofSeconds(1)));
    }

    @Test
    void aMissingDelegateOrLoggerFailsAtConstruction() {
        HttpClient plain = HttpClient.newHttpClient();

        assertThrows(NullPointerException.class, () -> LoggingHttpClient.wrap(plain, null));
        assertThrows(NullPointerException.class, () -> LoggingHttpClient.wrap(null, logger));
        assertThrows(NullPointerException.class, () -> LoggingHttpClient.wrap(plain, logger, null));
    }

    /** Hands back a future that never completes, so the identity assertion is the only news. */
    private static final class RecordingClient extends DelegatingHttpClient {
        private final java.util.concurrent.CompletableFuture<HttpResponse<Object>> pending =
                new java.util.concurrent.CompletableFuture<>();

        RecordingClient() {
            super(HttpClient.newHttpClient());
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException("not sent");
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            return (java.util.concurrent.CompletableFuture<HttpResponse<T>>) (Object) pending;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return (java.util.concurrent.CompletableFuture<HttpResponse<T>>) (Object) pending;
        }
    }
}
