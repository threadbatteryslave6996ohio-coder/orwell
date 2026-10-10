package dev.orwell.reverseproxy;

import dev.orwell.logging.LogEntry;
import dev.orwell.logging.Logger;
import dev.orwell.testing.PostgresJdbcTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against a real Postgres: the point of this class is the schema and the SQL, and an
 * in-memory double would test neither. The writer is asynchronous, so every assertion waits for
 * the row rather than assuming it has landed.
 */
class JdbcAuditStoreTest extends PostgresJdbcTest {

    /** What the endpoint knows about itself; constant per process, so constant per test. */
    private static final ProxyAuditRecord.Context CONTEXT =
            new ProxyAuditRecord.Context("http://nginx", "pattern-policy");

    private final List<LogEntry> logged = Collections.synchronizedList(new ArrayList<>());
    private final Logger logger = logged::add;

    private JdbcAuditStore store;

    @AfterEach
    void closeStore() throws SQLException {
        if (store != null) {
            store.close();
            store = null;
        }
        execute("DROP TABLE IF EXISTS request_audit");
    }

    private JdbcAuditStore store(Duration retention) {
        store = new JdbcAuditStore(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), 1000, retention, logger);
        return store;
    }

    private JdbcAuditStore store() {
        return store(Duration.ofDays(30));
    }

    private static ProxyRequest request(String method, String path, String query, Map<String, List<String>> headers) {
        return new ProxyRequest(method, path, query, headers, "10.0.0.9");
    }

    /**
     * A plain successful record for the cases that care only that a row exists, or which path it
     * carries. The tests that are about a particular column build their own.
     */
    private static ProxyAuditRecord proxied(String path) {
        return ProxyAuditRecord.proxied(
                Instant.now(), request("GET", path, null, Map.of()), CONTEXT, 200, 0, 4, 1, 1);
    }

    /** Polls until the supplier answers, so an async writer does not make the test flaky. */
    private static <T> T eventually(Supplier<T> supplier) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        T last = null;
        while (System.nanoTime() < deadline) {
            last = supplier.get();
            if (last != null) {
                return last;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return last;
    }

    private static Long countWhere(String where, Object... parameters) {
        String sql = "SELECT count(*) FROM request_audit" + (where.isEmpty() ? "" : " WHERE " + where);
        try (Connection connection = connection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Map<String, Object> onlyRow() {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT * FROM request_audit ORDER BY id")) {
            if (!rows.next()) {
                return null;
            }
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                row.put(rows.getMetaData().getColumnName(column), rows.getObject(column));
            }
            return row;
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void aProxiedRequestIsStoredWithItsSizesTimingAndService() {
        store().submit(ProxyAuditRecord.proxied(
                Instant.now(),
                request("GET", "/api/items", "limit=2", Map.of("Service", List.of("log-analyzer"))),
                CONTEXT, 200, 11, 512, 42, 30));

        Map<String, Object> row = eventually(JdbcAuditStoreTest::onlyRow);

        assertNotNull(row, "no row was written");
        assertEquals("PROXIED", row.get("outcome"));
        assertEquals("log-analyzer", row.get("service"));
        assertEquals("GET", row.get("method"));
        assertEquals("/api/items", row.get("path"));
        assertEquals("limit=2", row.get("query"));
        assertEquals("10.0.0.9", row.get("remote_address"));
        assertEquals(200, row.get("status"));
        assertEquals(11, row.get("request_bytes"));
        assertEquals(512, row.get("response_bytes"));
        assertEquals(42L, row.get("duration_ms"));
        // The proxy's own cost is duration minus upstream; storing one number could not say it.
        assertEquals(30L, row.get("upstream_ms"));
        assertNull(row.get("policy"));
        assertNull(row.get("detail"));
        assertNotNull(row.get("received_at"));
    }

    /**
     * The four "who" columns answer different questions, and a row is only useful for an audit if
     * they are all on it: the socket, what the hop in front claims, the calling process, and the
     * authenticated identity.
     */
    @Test
    void everyWayOfIdentifyingACallerIsStoredSeparately() {
        store().submit(ProxyAuditRecord.proxied(
                Instant.now(),
                request("GET", "/api/items", null, Map.of(
                        "Service", List.of("insta"),
                        "X-Client-Id", List.of("insta-worker"),
                        "X-Forwarded-For", List.of("203.0.113.7"),
                        "User-Agent", List.of("java-http-client/25"))),
                CONTEXT, 200, 0, 4, 1, 1));

        Map<String, Object> row = eventually(JdbcAuditStoreTest::onlyRow);

        assertNotNull(row, "no row was written");
        assertEquals("insta", row.get("service"));
        assertEquals("insta-worker", row.get("client_id"));
        assertEquals("10.0.0.9", row.get("remote_address"));
        assertEquals("203.0.113.7", row.get("forwarded_for"));
        assertEquals("java-http-client/25", row.get("user_agent"));
    }

    /**
     * Which proxy wrote the row, and what it was enforcing. Repeated on every row on purpose: two
     * proxies sharing this table have nothing else to tell them apart.
     */
    @Test
    void eachRowSaysWhichProxyWroteItAndWhatWasInForce() {
        store().submit(ProxyAuditRecord.blocked(
                Instant.now(), request("GET", "/admin/users", null, Map.of()), CONTEXT, "pattern-policy"));

        Map<String, Object> row = eventually(JdbcAuditStoreTest::onlyRow);

        assertNotNull(row, "no row was written");
        assertEquals("http://nginx", row.get("upstream"));
        assertEquals("pattern-policy", row.get("policies"));
    }

    /** An id is always present, so a log line and its row can always be joined. */
    @Test
    void theRequestIdIsCarriedThroughFromTheCallerAndMintedWhenAbsent() {
        JdbcAuditStore store = store();
        store.submit(ProxyAuditRecord.proxied(Instant.now(),
                request("GET", "/correlated", null, Map.of("X-Request-Id", List.of("caller-supplied-id"))),
                CONTEXT, 200, 0, 4, 1, 1));
        store.submit(proxied("/uncorrelated"));

        assertEquals(1L, eventually(() -> nonZero(countWhere("request_id = ?", "caller-supplied-id"))));
        assertEquals(2L, eventually(() -> nonZero(countWhere("request_id IS NOT NULL"))));
    }

    /**
     * An open pass-through stores an empty string, not NULL. NULL is what a row written before the
     * column existed carries, and "no policies ran" must not be indistinguishable from "unknown".
     */
    @Test
    void anEmptyPolicyChainIsStoredAsEmptyRatherThanNull() {
        store().submit(ProxyAuditRecord.proxied(Instant.now(), request("GET", "/open", null, Map.of()),
                new ProxyAuditRecord.Context("http://nginx", ""), 200, 0, 4, 1, 1));

        Map<String, Object> row = eventually(JdbcAuditStoreTest::onlyRow);

        assertNotNull(row, "no row was written");
        assertEquals("", row.get("policies"));
    }

    @Test
    void whatDoesNotApplyToAnOutcomeIsStoredAsNullRatherThanZero() {
        store().submit(ProxyAuditRecord.blocked(
                Instant.now(), request("GET", "/admin/users", null, Map.of()), CONTEXT, "pattern-policy"));

        Map<String, Object> row = eventually(JdbcAuditStoreTest::onlyRow);

        assertNotNull(row, "no row was written");
        assertEquals("BLOCKED", row.get("outcome"));
        assertEquals(403, row.get("status"));
        assertEquals("pattern-policy", row.get("policy"));
        // A blocked request never reached the upstream, so it has no sizes, no timing, and — this
        // being an unattributed, unauthenticated caller — no service and no client id.
        assertNull(row.get("service"));
        assertNull(row.get("client_id"));
        assertNull(row.get("query"));
        assertNull(row.get("request_bytes"));
        assertNull(row.get("response_bytes"));
        assertNull(row.get("duration_ms"));
        assertNull(row.get("upstream_ms"));
    }

    @Test
    void aRejectionAndAnUpstreamFailureBothKeepTheirReasonInDetail() {
        JdbcAuditStore store = store();
        store.submit(ProxyAuditRecord.rejected(
                Instant.now(), request("PUT", "/upload", null, Map.of()), CONTEXT, 413, "request body too large"));
        store.submit(ProxyAuditRecord.failed(
                Instant.now(), request("GET", "/x", null, Map.of()), CONTEXT, "java.net.ConnectException", 7, 5L));

        assertEquals(1L, eventually(() -> nonZero(countWhere("outcome = 'REJECTED' AND detail = ?",
                "request body too large"))));
        assertEquals(1L, eventually(() -> nonZero(countWhere("outcome = 'FAILED' AND status = 502"))));
        // A failure keeps its upstream timing, which is what separates a refused connection from a
        // request that sat until the timeout — the same 502 to the client, very different problems.
        assertEquals(1L, countWhere("outcome = 'FAILED' AND upstream_ms = 5"));
    }

    @Test
    void theTableAnswersTheQuestionsItExistsFor() {
        JdbcAuditStore store = store();
        Instant now = Instant.now();
        store.submit(ProxyAuditRecord.proxied(now, request("GET", "/a", null,
                Map.of("Service", List.of("insta"), "X-Client-Id", List.of("insta-worker"))),
                CONTEXT, 200, 0, 4, 1, 1));
        store.submit(ProxyAuditRecord.proxied(now, request("GET", "/b", null,
                Map.of("Service", List.of("insta"))), CONTEXT, 500, 0, 4, 1, 1));
        store.submit(ProxyAuditRecord.blocked(now, request("GET", "/admin/x", null,
                Map.of("Service", List.of("keeboarder-server"))), CONTEXT, "pattern-policy"));
        store.submit(proxied("/c"));

        assertEquals(4L, eventually(() -> countWhere("").equals(4L) ? 4L : null));

        // "what did one service do", "what did one identity do", "what got refused",
        // "what is still unattributed", "what failed"
        assertEquals(2L, countWhere("service = ?", "insta"));
        assertEquals(1L, countWhere("client_id = ?", "insta-worker"));
        assertEquals(1L, countWhere("outcome <> 'PROXIED'"));
        assertEquals(1L, countWhere("service IS NULL"));
        assertEquals(1L, countWhere("status >= 500"));
    }

    @Test
    void theSweepDropsRowsPastTheRetentionWindowAndKeepsTheRest() throws SQLException {
        JdbcAuditStore store = store(Duration.ofDays(30));
        store.submit(proxied("/fresh"));
        assertEquals(1L, eventually(() -> nonZero(countWhere(""))));

        insertAged(Instant.now().minus(40, ChronoUnit.DAYS));
        assertEquals(2L, countWhere(""));

        // The sweep runs hourly on the writer thread; the test drives it rather than waiting.
        store.sweep();

        assertEquals(1L, countWhere(""));
        assertEquals(1L, countWhere("path = ?", "/fresh"));
    }

    @Test
    void aZeroRetentionWindowKeepsEverything() throws SQLException {
        JdbcAuditStore store = store(Duration.ZERO);
        insertAged(Instant.now().minus(4000, ChronoUnit.DAYS));

        store.sweep();

        // Duration.ZERO means "keep forever", not "delete everything older than now" — the one
        // reading of it that would be a data-loss bug.
        assertEquals(1L, countWhere(""));
    }

    @Test
    void aFullQueueDropsTheRecordRatherThanBlockingTheRequestPath() {
        // Depth 1 with a writer that cannot keep up: the point is that submit() returns either
        // way, and that what it dropped is counted and said out loud.
        store = new JdbcAuditStore(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
                1, Duration.ZERO, logger);
        for (int index = 0; index < 500; index++) {
            store.submit(proxied("/burst/" + index));
        }

        assertTrue(store.droppedTotal() > 0, "expected the bounded queue to drop under a burst");
        assertTrue(logged.stream().anyMatch(entry -> entry.message().contains("Audit queue full")),
                () -> "expected a dropped-record warning, got " + logged);
    }

    @Test
    void anUnreachableDatabaseFailsAtStartupRatherThanAtTheFirstRequest() {
        // Port 1 on loopback: nothing listens, so this is a connection refusal, not a timeout.
        assertThrows(RuntimeException.class, () -> new JdbcAuditStore(
                "jdbc:postgresql://127.0.0.1:1/proxy", "proxy", "proxy", 10, Duration.ZERO, logger));
    }

    @Test
    void closingDrainsWhatIsStillQueued() {
        JdbcAuditStore store = store();
        for (int index = 0; index < 50; index++) {
            store.submit(proxied("/drain/" + index));
        }

        store.close();
        this.store = null;

        assertEquals(50L, countWhere(""));
    }

    private static Long nonZero(Long count) {
        return count == null || count == 0 ? null : count;
    }

    private static void insertAged(Instant receivedAt) throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO request_audit (received_at, outcome, method, path, status) "
                             + "VALUES (?, 'PROXIED', 'GET', '/old', 200)")) {
            statement.setObject(1, OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC));
            statement.executeUpdate();
        }
    }
}
