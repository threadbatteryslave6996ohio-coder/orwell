package dev.clippy.clients.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.clippy.clients.core.env.ClientAuthSession;
import dev.clippy.utils.ClipboardLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineClipboardSyncAppTest {
    @TempDir
    Path tempDir;

    @Test
    void readsClipboardEntriesAndIgnoresAuthAuditEntries() throws Exception {
        Path log = tempDir.resolve("offline.json");
        String content = """
                [
                  {"clientId":"client-a","content":"offline text","timestamp":"2026-06-23T12:00:00Z"},
                  {"type":"auth","clientId":"client-a","operation":"refresh","timestamp":"2026-06-23T12:01:00Z"}
                ]
                """;

        List<ClipboardRecord> records = OfflineSnapshotParser.parseRecords(content, log);

        assertEquals(1, records.size());
        assertEquals("offline text", records.getFirst().content());
        assertEquals(Instant.parse("2026-06-23T12:00:00Z"), records.getFirst().timestamp());
    }

    @Test
    void skipsLegacyOversizedClipboardEntriesWithoutContactingServer() throws Exception {
        Path log = tempDir.resolve("offline.json");
        String content = """
                [{"clientId":"client-a","content":"%s","timestamp":"2026-06-23T12:00:00Z"}]
                """.formatted("x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS + 1));

        List<ClipboardRecord> records = OfflineSnapshotParser.parseRecords(content, log);
        OfflineSyncService service = syncService(URI.create("http://localhost:1/clipboard"));

        assertEquals(1, records.size());
        assertEquals(new SyncResult(0, 0), service.sync(records));
    }

    @Test
    void excludesOversizedOldClientBeforeOwnershipValidation() throws Exception {
        List<ClipboardRecord> records = List.of(
                new ClipboardRecord(
                        "old-client",
                        "x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS + 1),
                        Instant.parse("2026-06-23T11:00:00Z")),
                new ClipboardRecord(
                        "client-a", "valid", Instant.parse("2026-06-23T12:00:00Z"))
        );

        List<ClipboardRecord> syncable = OfflineSyncService.syncableRecords(records, false);

        assertEquals(1, syncable.size());
        assertEquals("client-a", syncable.getFirst().clientId());
    }

    @Test
    void queriesTimeframeAndPostsOnlyMissingEntriesWithOriginalTimestamp() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> handleClipboard(exchange, requests));
        server.start();

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard");
            OfflineSyncService service = syncService(endpoint);
            List<ClipboardRecord> records = List.of(
                    new ClipboardRecord(
                            "client-a", "already there", Instant.parse("2026-06-23T12:00:00.123456789Z")),
                    new ClipboardRecord(
                            "client-a", "send me", Instant.parse("2026-06-23T13:00:00Z"))
            );

            SyncResult result = service.sync(records);

            assertEquals(1, result.alreadyPresent());
            assertEquals(1, result.sent());
            assertEquals(0, result.rejected());
            assertEquals(2, requests.size());
            assertTrue(requests.getFirst().startsWith("GET "));
            assertTrue(requests.getFirst().contains("from=2026-06-23T12%3A00%3A00.123455789Z"));
            assertTrue(requests.getFirst().contains("to=2026-06-23T13%3A00%3A00.000001Z"));
            assertTrue(requests.get(1).contains("\"content\":\"send me\""));
            assertTrue(requests.get(1).contains("\"timestamp\":\"2026-06-23T13:00:00Z\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void waitsThirtyMinutesThenSyncsNewFileEntries() throws Exception {
        List<String> requests = new ArrayList<>();
        List<Duration> sleepDurations = new ArrayList<>();
        List<String> clearedSnapshots = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> handleClipboard(exchange, requests));
        server.start();

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard");
            SyncMonitor monitor = monitor(endpoint);
            List<ClipboardRecord> changedRecords = List.of(
                    new ClipboardRecord(
                            "client-a", "send me", Instant.parse("2026-06-23T13:00:00Z"))
            );
            ClipboardSnapshot changedSnapshot = new ClipboardSnapshot("[changed]", changedRecords);
            monitor.monitor(() -> changedSnapshot, ignored -> { },
                    snapshot -> {
                        clearedSnapshots.add(snapshot.content());
                        return true;
                    },
                    new ClipboardSnapshot("[]", List.of()),
                    SyncMonitor.DEFAULT_SYNC_INTERVAL, duration -> {
                sleepDurations.add(duration);
                if (sleepDurations.size() == 2) {
                    throw new InterruptedException("stop test loop");
                }
            });
        } catch (InterruptedException expected) {
            assertEquals("stop test loop", expected.getMessage());
        } finally {
            server.stop(0);
        }

        assertEquals(List.of(Duration.ofMinutes(30), Duration.ofMinutes(30)), sleepDurations);
        assertEquals(List.of("[]", "[changed]"), clearedSnapshots);
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().startsWith("GET "));
        assertTrue(requests.get(1).contains("\"content\":\"send me\""));
    }

    @Test
    void retriesInitialReadFailureInsteadOfExiting() throws Exception {
        List<Duration> sleepDurations = new ArrayList<>();
        List<ClipboardRecord> expectedRecords = List.of(
                new ClipboardRecord(
                        "client-a", "available later", Instant.parse("2026-06-23T14:00:00Z"))
        );
        int[] attempts = {0};

        ClipboardSnapshot expectedSnapshot = new ClipboardSnapshot("[available]", expectedRecords);
        ClipboardSnapshot snapshot = SyncMonitor.awaitInitialSnapshot(
                () -> {
                    if (attempts[0]++ == 0) {
                        throw new IOException("file does not exist yet");
                    }
                    return expectedSnapshot;
                },
                sleepDurations::add
        );

        assertEquals(expectedSnapshot, snapshot);
        assertEquals(2, attempts[0]);
        assertEquals(List.of(Duration.ofSeconds(5)), sleepDurations);
    }

    @Test
    void waitsForUsableRecordsBeforeDerivingClientId() throws Exception {
        List<Duration> sleepDurations = new ArrayList<>();
        List<ClipboardRecord> oversizedOnly = List.of(
                new ClipboardRecord(
                        "client-a",
                        "x".repeat(ClipboardLimits.MAX_CONTENT_CHARACTERS + 1),
                        Instant.parse("2026-06-23T16:00:00Z"))
        );
        List<ClipboardRecord> usableRecords = List.of(
                new ClipboardRecord(
                        "client-a",
                        "usable",
                        Instant.parse("2026-06-23T16:05:00Z"))
        );
        int[] attempts = {0};

        ClipboardSnapshot snapshot = SyncMonitor.awaitInitialSyncableSnapshot(
                () -> {
                    if (attempts[0]++ == 0) {
                        return new ClipboardSnapshot("[oversized]", oversizedOnly);
                    }
                    return new ClipboardSnapshot("[usable]", usableRecords);
                }, ignored -> { }, ignored -> true,
                SyncMonitor.DEFAULT_SYNC_INTERVAL,
                sleepDurations::add
        );

        assertEquals(new ClipboardSnapshot("[usable]", usableRecords), snapshot);
        assertEquals(2, attempts[0]);
        assertEquals(List.of(Duration.ofMinutes(30)), sleepDurations);
    }

    @Test
    void doesNotClearSnapshotWhenSyncFails() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> respond(exchange, 503, "temporarily unavailable"));
        server.start();
        int[] clearAttempts = {0};

        try {
            URI endpoint = URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard");
            SyncMonitor monitor = monitor(endpoint);
            ClipboardSnapshot snapshot = new ClipboardSnapshot(
                    "[failed]",
                    List.of(new ClipboardRecord(
                            "client-a", "keep me", Instant.parse("2026-06-23T15:00:00Z")))
            );

            monitor.monitor(() -> snapshot, ignored -> { }, ignored -> {
                clearAttempts[0]++;
                return true;
            }, snapshot, Duration.ofMinutes(30), ignored -> {
                throw new InterruptedException("stop test loop");
            });
        } catch (InterruptedException expected) {
            assertEquals("stop test loop", expected.getMessage());
        } finally {
            server.stop(0);
        }

        assertEquals(0, clearAttempts[0]);
    }

    @Test
    void deadLettersMalformedEntryAndKeepsValidEntrySyncable() {
        String content = """
                [
                  {"clientId":"client-a","content":"missing timestamp"},
                  {"clientId":"client-a","content":"valid","timestamp":"2026-06-23T12:00:00Z"}
                ]
                """;

        ClipboardSnapshot snapshot = OfflineSnapshotParser.parseSnapshot(content, tempDir.resolve("offline.json"));

        assertEquals(1, snapshot.records().size());
        assertEquals("valid", snapshot.records().getFirst().content());
        assertEquals(1, snapshot.rejections().size());
        assertEquals("invalid-entry", snapshot.rejections().getFirst().stage());
        assertTrue(snapshot.rejections().getFirst().reason().contains("timestamp"));
    }

    @Test
    void deadLettersUnknownRecordTypeInsteadOfDroppingIt() {
        String content = """
                [
                  {"type":"future-event","clientId":"client-a","timestamp":"2026-06-23T12:00:00Z"},
                  {"type":"auth","clientId":"client-a","operation":"refresh","timestamp":"2026-06-23T12:01:00Z"}
                ]
                """;

        ClipboardSnapshot snapshot = OfflineSnapshotParser.parseSnapshot(content, tempDir.resolve("offline.json"));

        assertEquals(0, snapshot.records().size());
        assertEquals(1, snapshot.rejections().size());
        assertEquals("unknown-type", snapshot.rejections().getFirst().stage());
        assertTrue(snapshot.rejections().getFirst().reason().contains("future-event"));
    }

    @Test
    void deadLettersPermanentPostRejectionAndContinuesWithLaterRecord() throws Exception {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[]");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            respond(exchange, body.contains("reject me") ? 400 : 201, "");
        });
        server.start();
        List<RejectedRecord> rejections = new ArrayList<>();

        try {
            OfflineSyncService service = syncService(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard"));
            List<ClipboardRecord> records = List.of(
                    new ClipboardRecord(
                            "client-a", "reject me", Instant.parse("2026-06-23T12:00:00Z")),
                    new ClipboardRecord(
                            "client-a", "send me", Instant.parse("2026-06-23T12:01:00Z")));

            SyncResult result = service.sync(records, rejections::add);

            assertEquals(new SyncResult(0, 1, 1), result);
            assertEquals(2, requests.size());
            assertEquals(1, rejections.size());
            assertEquals("HTTP 400", rejections.getFirst().reason());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesSnapshotWhenDeadLetterWriteFails() throws Exception {
        SyncMonitor monitor = monitor(URI.create("http://localhost:1/clipboard"));
        ClipboardSnapshot snapshot = new ClipboardSnapshot(
                "[malformed]", List.of(), List.of(new RejectedRecord(
                        "invalid-entry", "bad entry", "{}")));
        int[] clearAttempts = {0};

        InterruptedException stopped = assertThrows(InterruptedException.class, () -> monitor.monitor(
                () -> snapshot,
                ignored -> { throw new IOException("dead-letter unavailable"); },
                ignored -> { clearAttempts[0]++; return true; },
                snapshot,
                SyncMonitor.DEFAULT_SYNC_INTERVAL,
                ignored -> { throw new InterruptedException("stop test loop"); }));

        assertEquals("stop test loop", stopped.getMessage());
        assertEquals(0, clearAttempts[0]);
    }

    @Test
    void stopsAfterFifthRetry() {
        List<Duration> delays = new ArrayList<>();
        int[] attempts = {0};

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SyncMonitor.awaitInitialSnapshot(
                        () -> { attempts[0]++; throw new IOException("still unavailable"); },
                        delays::add));

        assertEquals(6, attempts[0]);
        assertEquals(List.of(
                Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20),
                Duration.ofSeconds(40), Duration.ofSeconds(80)), delays);
        assertTrue(failure.getMessage().contains("Stopped after 5 retries"));
    }

    @Test
    void stopsSynchronizationAfterFifthRetryWithoutClearingSource() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        int[] requests = {0};
        server.createContext("/clipboard", exchange -> {
            requests[0]++;
            respond(exchange, 503, "temporarily unavailable");
        });
        server.start();
        List<Duration> delays = new ArrayList<>();
        int[] clearAttempts = {0};

        try {
            SyncMonitor monitor = monitor(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard"));
            ClipboardSnapshot snapshot = new ClipboardSnapshot(
                    "[retry]", List.of(new ClipboardRecord(
                            "client-a", "keep me", Instant.parse("2026-06-23T12:00:00Z"))));

            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> monitor.monitor(
                    () -> snapshot, ignored -> { }, ignored -> { clearAttempts[0]++; return true; },
                    snapshot, SyncMonitor.DEFAULT_SYNC_INTERVAL, delays::add));

            assertTrue(failure.getMessage().contains("Stopped after 5 retries"));
            assertEquals(6, requests[0]);
            assertEquals(0, clearAttempts[0]);
            assertEquals(List.of(
                    Duration.ofSeconds(5), Duration.ofSeconds(10), Duration.ofSeconds(20),
                    Duration.ofSeconds(40), Duration.ofSeconds(80)), delays);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void producesStableDeadLetterPayloadForIdempotentAppendRetries() throws Exception {
        RejectedRecord rejection = new RejectedRecord(
                "invalid-entry", "missing timestamp", "{\"content\":\"keep me\"}");

        assertEquals(rejection.toJson(), rejection.toJson());
        assertTrue(rejection.toJson().contains("\"type\":\"dead-letter\""));
    }

    @Test
    void resetsToNormalIntervalAfterRetrySucceeds() throws Exception {
        int[] requests = {0};
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/clipboard", exchange -> {
            if (requests[0]++ == 0) {
                respond(exchange, 503, "temporarily unavailable");
            } else if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "[]");
            } else {
                respond(exchange, 201, "{}");
            }
        });
        server.start();
        List<Duration> delays = new ArrayList<>();

        try {
            SyncMonitor monitor = monitor(
                    URI.create("http://localhost:" + server.getAddress().getPort() + "/clipboard"));
            ClipboardSnapshot snapshot = new ClipboardSnapshot(
                    "[retry]", List.of(new ClipboardRecord(
                            "client-a", "send me", Instant.parse("2026-06-23T12:00:00Z"))));

            assertThrows(InterruptedException.class, () -> monitor.monitor(
                    () -> snapshot, ignored -> { }, ignored -> true, snapshot,
                    SyncMonitor.DEFAULT_SYNC_INTERVAL, delay -> {
                        delays.add(delay);
                        if (delays.size() == 2) {
                            throw new InterruptedException("stop test loop");
                        }
                    }));
        } finally {
            server.stop(0);
        }

        assertEquals(List.of(Duration.ofSeconds(5), Duration.ofMinutes(30)), delays);
    }

    private static OfflineSyncService syncService(URI endpoint) {
        ClientAuthSession auth = new ClientAuthSession(null, "client-a", null, "token-a");
        return new OfflineSyncService(new RemoteClipboardGateway(endpoint, auth, "client-a"), "client-a");
    }

    private static SyncMonitor monitor(URI endpoint) {
        return new SyncMonitor(syncService(endpoint), "client-a");
    }

    private static void handleClipboard(HttpExchange exchange, List<String> requests) throws IOException {
        if (!"Bearer token-a".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            respond(exchange, 401, "");
            return;
        }
        if ("GET".equals(exchange.getRequestMethod())) {
            requests.add("GET " + exchange.getRequestURI().getRawQuery());
            respond(exchange, 200, """
                    [{"id":1,"clientId":"client-a","content":"already there","timestamp":"2026-06-23T12:00:00.123457Z"}]
                    """);
            return;
        }
        requests.add("POST " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        respond(exchange, 201, "{}");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
