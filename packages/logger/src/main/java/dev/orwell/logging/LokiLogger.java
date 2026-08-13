package dev.orwell.logging;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
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
 * Pushes entries straight to Loki's {@code /loki/api/v1/push} endpoint.
 *
 * <p><strong>The caller is never blocked and never fails.</strong> {@link #log(LogEntry)} does a
 * non-blocking offer onto a bounded queue and returns; a single daemon thread batches and ships.
 * When the queue is full — Loki down, slow, or simply a burst — entries are dropped and counted
 * rather than blocking a request thread. That tradeoff is the whole point: a logging call sitting
 * on a request path must never become the reason a request is slow.
 *
 * <p>Because the buffer is in memory, entries queued but not yet shipped are lost if the process
 * dies. That is the accepted cost of pushing directly rather than writing a file for a collector
 * to tail.
 *
 * <p>Where losing them is not acceptable, give the sink a <strong>fallback</strong>: every entry
 * Loki did not take — because the queue was full, because the push failed, or because Loki
 * answered an error — is handed to that logger instead of only being counted. {@code
 * LoggerMode.LOKI_WITH_FALLBACK} wires a {@link JsonLogger} in there, so an outage costs a file on
 * disk rather than the records themselves. The fallback is only touched on the failure path, so a
 * healthy Loki costs no disk I/O at all. One batch still escapes it: the one in flight when
 * {@link #close()} interrupts the worker, since a file write on an interrupted thread fails too.
 *
 * <p>Loki requires entries within a stream to be ordered by timestamp, so each entry is stamped on
 * arrival, and a batch is grouped by label set and sorted before it is sent.
 */
public final class LokiLogger implements Logger, AutoCloseable {
    private static final int DEFAULT_QUEUE_CAPACITY = 10_000;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofSeconds(2);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration DROP_REPORT_INTERVAL = Duration.ofMinutes(5);

    /** An entry plus the instant it was recorded, so batching does not smear timestamps. */
    private record Queued(Instant at, LogEntry entry) {
    }

    private final URI endpoint;
    private final String tenantId;
    private final Map<String, String> baseLabels;
    private final BlockingQueue<Queued> queue;
    private final int batchSize;
    private final Duration flushInterval;
    private final HttpClient http;
    private final ObjectMapper mapper;
    /** Where entries go when Loki will not take them. Null means they are only counted. */
    private final Logger fallback;

    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastDropReport;
    private volatile boolean running = true;
    private final Thread worker;

    public LokiLogger(String appName, URI endpoint, String tenantId) {
        this(appName, endpoint, tenantId, null);
    }

    /** With a sink for the entries Loki does not take; null keeps the drop-and-count behavior. */
    public LokiLogger(String appName, URI endpoint, String tenantId, Logger fallback) {
        this(appName, endpoint, tenantId, DEFAULT_QUEUE_CAPACITY, DEFAULT_BATCH_SIZE,
                DEFAULT_FLUSH_INTERVAL, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
                fallback);
    }

    public LokiLogger(
            String appName,
            URI endpoint,
            String tenantId,
            int queueCapacity,
            int batchSize,
            Duration flushInterval,
            HttpClient http
    ) {
        this(appName, endpoint, tenantId, queueCapacity, batchSize, flushInterval, http, null);
    }

    public LokiLogger(
            String appName,
            URI endpoint,
            String tenantId,
            int queueCapacity,
            int batchSize,
            Duration flushInterval,
            HttpClient http,
            Logger fallback
    ) {
        this.fallback = fallback;
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.tenantId = tenantId == null || tenantId.isBlank() ? null : tenantId;
        this.baseLabels = Map.of("app", Objects.requireNonNull(appName, "appName"), "stream_type", "app");
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.batchSize = batchSize;
        this.flushInterval = Objects.requireNonNull(flushInterval, "flushInterval");
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = new ObjectMapper();
        this.worker = Thread.ofPlatform()
                .name("loki-logger-" + appName)
                .daemon(true)
                .start(this::drainLoop);
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        if (!queue.offer(new Queued(Instant.now(), entry))) {
            noteDrop();
            divert(entry);
        }
    }

    /** Flushes what is queued and stops the worker. Spring calls this on context shutdown. */
    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(flushInterval.toMillis() + REQUEST_TIMEOUT.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        drainOnce();
    }

    /**
     * Entries Loki did not receive — queued behind a full buffer, or in a batch it refused.
     * Exposed for tests and diagnostics. A configured fallback was given those entries, so this
     * counts what Loki missed, not necessarily what was lost.
     */
    public long droppedEntries() {
        return dropped.get();
    }

    private void drainLoop() {
        while (running) {
            try {
                // Block for the first entry so an idle app does no work, then take whatever else
                // has accumulated rather than sending one request per line.
                Queued first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<Queued> batch = new ArrayList<>(batchSize);
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                send(batch);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                // A malformed batch must not kill the worker; that would silently end all shipping.
                noteFailure(failure);
            }
        }
    }

    private void drainOnce() {
        List<Queued> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            try {
                send(remaining);
            } catch (RuntimeException failure) {
                noteFailure(failure);
            }
        }
    }

    private void send(List<Queued> batch) {
        String body = payload(batch);
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (tenantId != null) {
            request.header("X-Scope-OrgID", tenantId);
        }
        try {
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                // No retry: entries are already stamped, and holding a batch to retry it would
                // stall newer entries behind it. Dropping keeps the pipeline moving — and where a
                // fallback is configured, the batch lands there rather than nowhere.
                dropped.addAndGet(batch.size());
                divert(batch);
                noteFailure(new IOException("Loki push returned HTTP " + response.statusCode()));
            }
        } catch (IOException failure) {
            dropped.addAndGet(batch.size());
            divert(batch);
            noteFailure(failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Builds Loki's push payload: one stream per distinct label set, each with its entries as
     * {@code [<epoch nanoseconds as string>, <line>]} pairs, sorted ascending — Loki rejects a
     * stream whose entries go backwards in time.
     */
    String payload(List<Queued> batch) {
        Map<Map<String, String>, List<Queued>> byLabels = new LinkedHashMap<>();
        for (Queued queued : batch) {
            byLabels.computeIfAbsent(labelsFor(queued.entry()), ignored -> new ArrayList<>()).add(queued);
        }

        List<Map<String, Object>> streams = new ArrayList<>(byLabels.size());
        byLabels.forEach((labels, entries) -> {
            entries.sort((left, right) -> left.at().compareTo(right.at()));
            List<List<String>> values = new ArrayList<>(entries.size());
            for (Queued queued : entries) {
                values.add(List.of(epochNanos(queued.at()), line(queued)));
            }
            Map<String, Object> stream = new LinkedHashMap<>();
            stream.put("stream", labels);
            stream.put("values", values);
            streams.add(stream);
        });

        try {
            return mapper.writeValueAsString(Map.of("streams", streams));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize Loki push payload.", exception);
        }
    }

    /**
     * Labels stay bounded: the app name, the constant stream type, and the level enum. Metadata is
     * deliberately never promoted — a per-client label would create a Loki stream per user.
     */
    private Map<String, String> labelsFor(LogEntry entry) {
        Map<String, String> labels = new LinkedHashMap<>(baseLabels);
        labels.put("level", entry.level().name());
        return labels;
    }

    private String line(Queued queued) {
        try {
            return mapper.writeValueAsString(LogEntryJson.payload(queued.entry(), queued.at()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize log entry.", exception);
        }
    }

    private static String epochNanos(Instant at) {
        return Long.toString(at.getEpochSecond() * 1_000_000_000L + at.getNano());
    }

    private void divert(List<Queued> batch) {
        for (Queued queued : batch) {
            divert(queued.entry());
        }
    }

    /**
     * Hands an entry Loki could not take to the fallback sink.
     *
     * <p>On the queue-full path this runs on the calling thread, so a caller can pay for a disk
     * write — but only while Loki is failing, and only because that is what asking for a fallback
     * means: the record matters more than the microseconds. The sink's promise not to block on the
     * network is unchanged.
     */
    private void divert(LogEntry entry) {
        if (fallback == null) {
            return;
        }
        try {
            fallback.log(entry);
        } catch (RuntimeException failure) {
            // Both sinks are now failing; stderr is all that is left, as in FailSafeLogger.
            System.err.println("Loki fallback sink failed; entry dropped: " + failure);
        }
    }

    private void noteDrop() {
        dropped.incrementAndGet();
        reportDropsPeriodically();
    }

    /**
     * Reported on an interval rather than once: a sink that is dropping is invisible from the
     * outside, and a single line at the start of an outage scrolls away long before anyone looks.
     */
    private void reportDropsPeriodically() {
        long now = System.currentTimeMillis();
        if (now - lastDropReport < DROP_REPORT_INTERVAL.toMillis()) {
            return;
        }
        synchronized (this) {
            if (now - lastDropReport < DROP_REPORT_INTERVAL.toMillis()) {
                return;
            }
            lastDropReport = now;
        }
        System.err.println("Loki sink dropped " + dropped.get() + " log entries so far (endpoint " + endpoint + ").");
    }

    private void noteFailure(Exception failure) {
        reportDropsPeriodically();
        System.err.println("Loki push failed: " + failure);
    }
}
