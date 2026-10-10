package dev.orwell.health;

import dev.orwell.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Runs the checks on a timer, forever.
 *
 * <p>{@code scheduleWithFixedDelay} rather than {@code scheduleAtFixedRate}, following
 * {@code FrameRetentionSchedule} and for a sharper version of the same reason: when several targets
 * are timing out a round takes as long as the timeout, and fixed-rate would queue the next round
 * immediately and keep stacking them — turning an outage into a growing pile of concurrent probes
 * against the service that is already struggling.
 *
 * <p>The first round runs immediately rather than after one interval, so a monitor that starts into
 * an already-broken stack says so in seconds instead of thirty.
 */
final class HealthMonitorSchedule implements AutoCloseable {
    /**
     * The {@code clientId} the heartbeat is published under.
     *
     * <p>Named {@code clientId} because that is the field {@code apps/liveness-analyzer} groups
     * heartbeats by — it reads it out of the line body with {@code | json}. Matching the existing
     * contract is worth more than a better-fitting name for a service that is not a client: it means
     * that app can watch this one with a configuration change and no code.
     */
    private static final String HEARTBEAT_CLIENT_ID = "health-monitor";

    private final HealthMonitorService service;
    private final Logger logger;
    private final Optional<Logger> heartbeat;
    private final int intervalSeconds;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "health-monitor");
                // Non-daemon: this thread *is* the job, and the process should stay alive for it
                // the way the retention worker's does.
                thread.setDaemon(false);
                return thread;
            });

    HealthMonitorSchedule(HealthMonitorService service, int intervalSeconds, Logger logger,
            Optional<Logger> heartbeat) {
        this.service = Objects.requireNonNull(service, "service");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.heartbeat = Objects.requireNonNull(heartbeat, "heartbeat");
        this.intervalSeconds = Math.max(1, intervalSeconds);
    }

    void start() {
        logger.info("Health checks are running.", Map.of(
                "targets", service.watches().size(),
                "intervalSeconds", intervalSeconds,
                // Said out loud, because without it nothing is watching the watchman and the log is
                // the only place that fact is visible.
                "heartbeat", heartbeat.isPresent() ? "loki" : "off"));
        scheduler.scheduleWithFixedDelay(this::round, 0, intervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * One round, with a catch-all around it.
     *
     * <p>A scheduled task that throws is <em>silently cancelled</em> by
     * {@code ScheduledExecutorService} — the future holds the exception and nothing ever looks at
     * it. For a monitor that failure is the worst one available: checking would stop, the process
     * would stay up and healthy, and the absence of alerts would read as everything being fine.
     */
    private void round() {
        try {
            service.checkAll();
            // After the round, never before: the heartbeat is evidence that checking happened, so
            // emitting it up front would make it evidence of nothing but the timer firing.
            beat();
        } catch (RuntimeException exception) {
            logger.error("A health check round failed; checks continue.",
                    Map.of("error", exception.toString()));
        }
    }

    /**
     * Publishes one heartbeat to the separate Loki stream.
     *
     * <p>This is what makes the monitor's own death detectable. Every other component announces its
     * failure <em>through this app</em>; this app's failure announces nothing, and silence is its
     * normal output — so it has to say something on a schedule and let a dead-man's switch watch for
     * the gap. {@code apps/liveness-analyzer} is that switch.
     *
     * <p>It carries the round counters, so the line is also the answer to "was it actually working",
     * not only "was the process alive".
     */
    private void beat() {
        heartbeat.ifPresent(sink -> {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("clientId", HEARTBEAT_CLIENT_ID);
            metadata.put("targets", service.watches().size());
            metadata.put("targetsDown", service.downCount());
            metadata.put("intervalSeconds", intervalSeconds);
            sink.info("Health monitor heartbeat.", metadata);
        });
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        service.close();
    }
}
