package dev.orwell.clients.core;

import dev.orwell.logging.Logger;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the shared runtime lifecycle for the desktop clipboard clients: the polling
 * scheduler, its shutdown hook, and the startup log line. Platform mains keep
 * only their clipboard-reader construction and hand the assembled monitor here.
 */
public final class DesktopClientRunner {
    private static final String SHUTDOWN_TIMEOUT_MESSAGE = "Timed out while stopping clipboard client.";

    private final DesktopClipboardMonitor monitor;
    private final long pollIntervalMs;
    private final long heartbeatIntervalMs;
    private final Logger logger;

    public DesktopClientRunner(
            DesktopClipboardMonitor monitor, long pollIntervalMs, long heartbeatIntervalMs, Logger logger) {
        this.monitor = Objects.requireNonNull(monitor, "monitor");
        this.pollIntervalMs = pollIntervalMs;
        this.heartbeatIntervalMs = heartbeatIntervalMs;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Logs the startup line, installs the shutdown hook, and schedules polling.
     *
     * @param label       message of the startup entry, e.g. {@code "Klippy client started."}
     * @param config      client configuration used to render the shared startup fields
     * @param extraFields additional startup metadata
     */
    public void start(String label, ClientConfig config, Map<String, String> extraFields) {
        logger.info(label, startupFields(config, pollIntervalMs, extraFields));

        startHeartbeat(config);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                ExecutorShutdown.shutdown(scheduler, SHUTDOWN_TIMEOUT_MESSAGE, logger)));
        scheduler.scheduleWithFixedDelay(this::pollSafely, 0, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts the liveness heartbeat shared by every desktop client, on its own scheduler and
     * cadence so an external monitor can tell the process is alive even when the clipboard is idle.
     * Its request timeout is kept short and under the interval (but positive) so a slow-but-alive
     * server cannot stretch the gap between logged beats toward the monitor's down-threshold.
     */
    private void startHeartbeat(ClientConfig config) {
        long timeoutMs = Math.min(Math.max(heartbeatIntervalMs, HeartbeatScheduler.MINIMUM_INTERVAL_MS), 3000L);
        ClipboardApiClient heartbeatApiClient =
                new ClipboardApiClient(config.endpoint(), config.authSession(), Duration.ofMillis(timeoutMs));
        new HeartbeatScheduler(
                heartbeatApiClient, config.endpoint().resolve("heartbeat"), config.clientId(),
                heartbeatIntervalMs, logger).start();
    }

    /**
     * {@code scheduleWithFixedDelay} cancels the task permanently the first time it throws, which
     * would leave the client alive but silently never syncing again. No recoverable failure a
     * single poll can hit — including a failure in the logging sink itself — is worth ending the
     * run, so runtime exceptions are contained here.
     *
     * <p>{@link Error} is deliberately not caught: an OutOfMemoryError or StackOverflowError means
     * the JVM is already unwell, and swallowing it every poll interval would hide that forever.
     */
    private void pollSafely() {
        try {
            monitor.poll();
        } catch (RuntimeException failure) {
            reportPollFailure(failure);
        }
    }

    private void reportPollFailure(RuntimeException failure) {
        try {
            logger.error("Clipboard poll failed; continuing.", Map.of("error", String.valueOf(failure)));
        } catch (RuntimeException ignored) {
            // The sink itself is broken; dropping this report beats killing the poll loop.
        }
    }

    public void start(String label, ClientConfig config) {
        start(label, config, Map.of());
    }

    static Map<String, Object> startupFields(
            ClientConfig config, long pollIntervalMs, Map<String, String> extraFields) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("clientId", config.clientId());
        fields.put("endpoint", String.valueOf(config.endpoint()));
        fields.put("authServer", config.authServerUrl() == null ? "unset" : config.authServerUrl());
        fields.put("pollIntervalMs", Long.toString(pollIntervalMs));
        fields.putAll(extraFields);
        fields.put("tokenSource", config.authSession().canRefresh() ? "CLIENT_SECRET" : "CLIENT_TOKEN");
        return fields;
    }
}
