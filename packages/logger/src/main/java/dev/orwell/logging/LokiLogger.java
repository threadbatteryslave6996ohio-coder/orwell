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

    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastDropReport;
    private volatile boolean running = true;
    private final Thread worker;

    public LokiLogger(String appName, URI endpoint, String tenantId) {
        this(appName, endpoint, tenantId, DEFAULT_QUEUE_CAPACITY, DEFAULT_BATCH_SIZE,
                DEFAULT_FLUSH_INTERVAL, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
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

    /** Entries dropped because the queue was full. Exposed for tests and diagnostics. */
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
                // stall newer entries behind it. Dropping keeps the pipeline moving.
                dropped.addAndGet(batch.size());
                noteFailure(new IOException("Loki push returned HTTP " + response.statusCode()));
            }
        } catch (IOException failure) {
            dropped.addAndGet(batch.size());
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
