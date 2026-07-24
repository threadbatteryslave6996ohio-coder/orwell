package dev.orwell.clients.core;

import dev.orwell.logging.Logger;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends a periodic liveness heartbeat to the remote server so an external monitor can tell the
 * client is still running. Runs on its own single-thread scheduler, independent of the clipboard
 * poll loop, because the heartbeat cadence (seconds) is deliberately decoupled from how often the
 * clipboard is read.
 *
 * <p>The server logs each received beat through its Loki-connected logger; the client itself stays
 * quiet on success and only logs when a beat cannot be delivered, so a 5-second heartbeat does not
 * flood the client console.
 */
public final class HeartbeatScheduler {
    private static final String SHUTDOWN_TIMEOUT_MESSAGE = "Timed out while stopping heartbeat scheduler.";

    /**
     * Floor for the heartbeat interval. A zero or negative delay makes
     * {@code scheduleWithFixedDelay} throw and would abort client startup, and anything faster than
     * this only adds request load without improving liveness resolution.
     */
    static final long MINIMUM_INTERVAL_MS = 1000L;

    private final ClipboardApiClient apiClient;
    private final URI heartbeatUri;
    private final String clientId;
    private final long intervalMs;
    private final Logger logger;
    private volatile boolean previousBeatFailed;

    public HeartbeatScheduler(
            ClipboardApiClient apiClient,
            URI heartbeatUri,
            String clientId,
            long intervalMs,
            Logger logger
    ) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.heartbeatUri = Objects.requireNonNull(heartbeatUri, "heartbeatUri");
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.logger = Objects.requireNonNull(logger, "logger");
        // Clamp rather than exit: an unusable heartbeat interval must not take the client down, the
        // way a bad clipboard-poll interval does — the heartbeat is a health signal, not core work.
        if (intervalMs < MINIMUM_INTERVAL_MS) {
            logger.warn("Heartbeat interval below minimum; using the minimum.", Map.of(
                    "configuredMs", intervalMs,
                    "minimumMs", MINIMUM_INTERVAL_MS));
            this.intervalMs = MINIMUM_INTERVAL_MS;
        } else {
            this.intervalMs = intervalMs;
        }
    }

    /** Installs the shutdown hook and starts beating at the configured interval. */
    public void start() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "client-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
                ExecutorShutdown.shutdown(scheduler, SHUTDOWN_TIMEOUT_MESSAGE, logger)));
        scheduler.scheduleWithFixedDelay(this::beatSafely, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * {@code scheduleWithFixedDelay} cancels the task permanently the first time it throws, which
     * would silently stop the heartbeat while the client keeps running — exactly the false-alive
     * signal this exists to avoid. So every failure is contained here and the loop lives on.
     */
    private void beatSafely() {
        try {
            HttpResponse<String> response = apiClient.heartbeat(heartbeatUri);
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                if (previousBeatFailed) {
                    logger.info("Heartbeat delivery recovered.", Map.of("clientId", clientId));
                    previousBeatFailed = false;
                }
            } else {
                reportFailure("Server rejected heartbeat with HTTP " + status);
            }
        } catch (Exception failure) {
            reportFailure(String.valueOf(failure));
        }
    }

    private void reportFailure(String message) {
        // De-duplicate across a failure streak so an unreachable server does not fill the console
        // with one line every interval; the monitor's alert comes from the absence of beats in Loki.
        if (previousBeatFailed) {
            return;
        }
        previousBeatFailed = true;
        try {
            logger.warn("Heartbeat delivery failed; will keep trying.", Map.of(
                    "clientId", clientId,
                    "heartbeatUri", String.valueOf(heartbeatUri),
                    "error", message));
        } catch (RuntimeException ignored) {
            // The sink itself is broken; dropping this report beats killing the heartbeat loop.
        }
    }
}
