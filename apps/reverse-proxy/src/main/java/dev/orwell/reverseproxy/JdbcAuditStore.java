package dev.orwell.reverseproxy;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.orwell.logging.Logger;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stores the audit trail in Postgres, behind the request path rather than in front of it.
 *
 * <p>The repo's rule that a database sink must never sit on a request path is why this is not a
 * {@code Logger} — {@code DatabaseLogger} was deleted for putting a round trip on every request,
 * and nothing here reintroduces it. Instead a request hands its record to a bounded queue and
 * returns; one virtual thread drains that queue in batches. A full queue drops the record and
 * counts it, because the alternatives are blocking the proxy on Postgres or growing the queue
 * until the heap is gone. It is the same shape as {@code jarvis-hub}'s {@code FrameStoreWriter},
 * for the same reason.
 *
 * <p>The trade that buys: a row can be lost — but only once the queue is full. A stalled or
 * restarting database costs queue depth rather than rows, because the writer waits on the pool
 * and drains the backlog when the database returns. The log line is never lost either way, since
 * every request also writes one through the shared {@link Logger}, so what a drop costs is the
 * queryability of the audit, not the audit.
 *
 * <p>Startup and steady state fail differently on purpose. A store that cannot connect or cannot
 * apply its schema aborts the boot: you asked for an audit trail and it cannot be kept, and a
 * proxy that silently never writes one is the worst of the outcomes. Once serving, a database
 * that goes away costs queue depth and, past that, dropped rows with an ERROR record — by then
 * requests are in flight, and killing the proxy would turn a Postgres outage into a total one.
 */
public final class JdbcAuditStore implements AuditStore {
    private static final String SCHEMA_RESOURCE = "/audit-schema.sql";
    private static final String INSERT = """
            INSERT INTO request_audit
                (received_at, outcome, request_id, service, client_id, method, path, query,
                 remote_address, forwarded_for, user_agent, upstream, policies, status,
                 policy, request_bytes, response_bytes, duration_ms, upstream_ms, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SWEEP = "DELETE FROM request_audit WHERE received_at < ?";

    /** Rows per transaction. */
    private static final int BATCH = 100;
    /** How long the writer waits for the first record of a batch before looping. */
    private static final long POLL_MILLIS = 200;
    /** How often the retention sweep runs, when it is enabled at all. */
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(1);
    /**
     * Longest text stored in any one column. A pathological URL or a stack-trace-shaped error
     * must not be able to bloat the table one row at a time; the truncation is visible in the
     * data rather than silent, because what is cut is the tail of a value nobody queries on.
     */
    private static final int MAX_TEXT = 2048;

    private final HikariDataSource dataSource;
    private final Logger logger;
    private final BlockingQueue<ProxyAuditRecord> queue;
    private final Duration retention;
    private final Thread writer;
    private final AtomicLong droppedTotal = new AtomicLong();
    private final AtomicLong writtenTotal = new AtomicLong();
    private volatile boolean running = true;

    public JdbcAuditStore(
            String jdbcUrl, String username, String password, int queueDepth, Duration retention, Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueDepth));

        HikariConfig configuration = new HikariConfig();
        configuration.setJdbcUrl(Objects.requireNonNull(jdbcUrl, "jdbcUrl"));
        configuration.setUsername(username);
        configuration.setPassword(password);
        // One writer thread and an occasional sweep: two connections is one more than is ever
        // needed at once, and this process must not be what exhausts the shared Postgres.
        configuration.setMaximumPoolSize(2);
        configuration.setPoolName("proxy-audit");
        this.dataSource = new HikariDataSource(configuration);

        try {
            applySchema(dataSource);
        } catch (SQLException exception) {
            dataSource.close();
            throw new IllegalStateException("Could not apply the audit schema to " + jdbcUrl, exception);
        }

        this.writer = Thread.ofVirtual().name("proxy-audit-writer").start(this::run);
        logger.info("Audit store ready.", Map.of(
                "queueDepth", queue.remainingCapacity(),
                "retentionDays", retention.isZero() ? "forever" : retention.toDays()));
    }

    @Override
    public void submit(ProxyAuditRecord record) {
        // offer, not put: the request path never waits on the audit trail.
        if (!queue.offer(record)) {
            long dropped = droppedTotal.incrementAndGet();
            // One line per drop would itself become the outage. Report the first, then decades
            // apart, which is enough to see it in the log without drowning it.
            if (dropped == 1 || dropped % 1000 == 0) {
                logger.warn("Audit queue full; the request is logged but not stored.", Map.of(
                        "droppedTotal", dropped,
                        "target", record.target()));
            }
        }
    }

    @Override
    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", true);
        details.put("pending", queue.size());
        details.put("writtenTotal", writtenTotal.get());
        details.put("droppedTotal", droppedTotal.get());
        return details;
    }

    /** Rows the writer has committed. Exposed for tests and {@code /health}. */
    public long writtenTotal() {
        return writtenTotal.get();
    }

    /** Records dropped because the queue was full — the audit's only lossy edge. */
    public long droppedTotal() {
        return droppedTotal.get();
    }

    private void run() {
        List<ProxyAuditRecord> batch = new ArrayList<>(BATCH);
        long lastSweep = System.nanoTime();
        while (running || !queue.isEmpty()) {
            try {
                ProxyAuditRecord first = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (first != null) {
                    batch.add(first);
                    queue.drainTo(batch, BATCH - 1);
                    write(batch);
                }
                if (sweepDue(lastSweep)) {
                    sweep();
                    lastSweep = System.nanoTime();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                // The rows in hand are lost, but every one of them was already logged, and the
                // writer has to survive to keep the next ones storable.
                droppedTotal.addAndGet(batch.size());
                logger.error("Could not store a batch of audit records; they stay log-only.", Map.of(
                        "records", batch.size(),
                        "error", exception.toString()));
            } finally {
                batch.clear();
            }
        }
    }

    private void write(List<ProxyAuditRecord> batch) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(INSERT)) {
            for (ProxyAuditRecord record : batch) {
                bind(statement, record);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        writtenTotal.addAndGet(batch.size());
    }

    private static void bind(PreparedStatement statement, ProxyAuditRecord record) throws SQLException {
        statement.setObject(1, OffsetDateTime.ofInstant(record.receivedAt(), ZoneOffset.UTC));
        statement.setString(2, record.outcome().name());
        setText(statement, 3, record.requestId());
        setText(statement, 4, record.service());
        setText(statement, 5, record.clientId());
        statement.setString(6, truncated(record.method()));
        statement.setString(7, truncated(record.path()));
        // "" means the request line carried no query at all; NULL says that in SQL.
        setText(statement, 8, record.query() == null || record.query().isEmpty() ? null : record.query());
        setText(statement, 9, record.remoteAddress());
        setText(statement, 10, record.forwardedFor());
        setText(statement, 11, record.userAgent());
        setText(statement, 12, record.upstream());
        // Not setText: an empty policy list is a fact ("this proxy ran no policies"), and storing
        // it as NULL would make it indistinguishable from a row written before the column existed.
        statement.setString(13, record.policies() == null ? null : truncated(record.policies()));
        statement.setInt(14, record.status());
        setText(statement, 15, record.policy());
        setInteger(statement, 16, record.requestBytes());
        setInteger(statement, 17, record.responseBytes());
        setBigInt(statement, 18, record.durationMs());
        setBigInt(statement, 19, record.upstreamMs());
        setText(statement, 20, record.detail());
    }

    private static void setText(PreparedStatement statement, int index, String value) throws SQLException {
        if (value == null || value.isEmpty()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, truncated(value));
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setBigInt(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String truncated(String value) {
        return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
    }

    private boolean sweepDue(long lastSweep) {
        return !retention.isZero() && System.nanoTime() - lastSweep >= SWEEP_INTERVAL.toNanos();
    }

    /**
     * Drops rows past the retention window. It runs on the writer thread rather than a timer, so
     * a sweep can never overlap a write or a second sweep, and so it costs nothing when the proxy
     * is idle. Package-private so a test can drive it without waiting an hour.
     */
    void sweep() {
        if (retention.isZero()) {
            // "Keep forever" is a property of the method, not just of its caller: a zero window
            // read as a cutoff of `now` would delete the entire table.
            return;
        }
        Instant cutoff = Instant.now().minus(retention);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(SWEEP)) {
            statement.setObject(1, OffsetDateTime.ofInstant(cutoff, ZoneOffset.UTC));
            int deleted = statement.executeUpdate();
            if (deleted > 0) {
                logger.info("Swept expired audit records.", Map.of(
                        "deleted", deleted,
                        "olderThan", cutoff.toString()));
            }
        } catch (SQLException exception) {
            // A failed sweep costs disk, not correctness. Say so and try again next hour.
            logger.error("Could not sweep expired audit records.", Map.of("error", exception.toString()));
        }
    }

    private static void applySchema(DataSource dataSource) throws SQLException {
        String ddl = readSchema();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(ddl);
        }
    }

    private static String readSchema() {
        try (InputStream stream = JdbcAuditStore.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + SCHEMA_RESOURCE + " on the classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Drains what is queued, so a clean shutdown does not lose the last few seconds of audit. */
    @Override
    public void close() {
        running = false;
        // Deliberately NOT writer.interrupt(): run()'s catch for InterruptedException returns
        // immediately, abandoning whatever is still queued — the exact loss this method exists to
        // avoid. `running = false` is enough, because the loop condition is
        // `running || !queue.isEmpty()`, so it keeps draining and then exits on its own.
        boolean drained = false;
        try {
            writer.join(TimeUnit.SECONDS.toMillis(5));
            drained = !writer.isAlive();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        // Only after the writer has actually stopped. Closing the pool under a still-draining
        // writer is worse than the loss it was meant to avoid: every remaining batch fails against
        // a dead pool and logs an error saying so, which reads as a database fault rather than a
        // shutdown. If it did not finish in time, say what was left rather than pretending.
        if (!drained) {
            int remaining = queue.size();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("queued", remaining);
            metadata.put("waitedSeconds", 5);
            logger.warn("Audit writer did not drain before shutdown; those records are lost.",
                    metadata);
        }
        dataSource.close();
    }
}
