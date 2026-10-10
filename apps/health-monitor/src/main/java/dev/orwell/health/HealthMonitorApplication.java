package dev.orwell.health;

import dev.orwell.env.Env;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LoggerSetup;
import dev.orwell.logging.ManagedLogger;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.server.HttpServerExchange;

import java.time.Duration;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The health monitor: polls other services' {@code /health} endpoints and says when one stops
 * answering.
 *
 * <pre>
 * GET /health    this app's own health, plus a one-line summary of every target
 * GET /targets   everything known about every target: state, last detail, latency, counters
 * </pre>
 *
 * <p><strong>What this adds over the compose health checks.</strong> Those already exist on most
 * services, and they do something different: Docker restarts a container that fails one, and tells
 * nobody. This tells somebody — one alert when a service goes down, one when it comes back — gives a
 * single place to see the whole stack at once, and works for anything with a URL, including
 * dependencies that are not in the compose file at all. It does not replace them; restart-on-failure
 * is still Docker's job.
 *
 * <p><strong>It is distinct from {@code apps/liveness-analyzer}</strong>, which is the other half of
 * the same question. That one is a dead-man's switch: it watches Loki for heartbeat lines that
 * desktop <em>clients</em> emit and alerts on their absence, so it covers things that cannot be
 * polled because nothing can reach them. This one actively probes <em>servers</em> that can be
 * reached. Neither subsumes the other; a client behind a home router needs the first, and a server
 * that is up but broken needs the second.
 *
 * <p><strong>Who watches the watchman.</strong> This app's failure is the one failure that announces
 * nothing — every other component's outage is reported <em>by</em> it, and silence is its normal
 * output. Running it on its own host removes the worst version of that (dying alongside the things it
 * watches) but not the likelier one: a process that is up with its schedule stopped, still answering
 * its port, still looking healthy, checking nothing. Two things cover it, and both are here:
 *
 * <ul>
 *   <li><strong>It publishes a heartbeat</strong> after every completed round, on its own Loki stream
 *       ({@code stream_type="heartbeat"}), carrying a {@code clientId} — which is the shape
 *       {@code apps/liveness-analyzer} already alerts on the absence of. The two apps then cover each
 *       other: this one probes the servers, that one watches this one. Needs {@code LOKI_URL}; without
 *       it the app says so at startup.
 *   <li><strong>Its own {@code /health} answers 503 when its rounds have stopped</strong>, so an
 *       external check on this endpoint is a real check rather than a port-open check.
 * </ul>
 *
 * <p>What remains is "Loki and the liveness analyzer are down too", which is a far smaller target than
 * "the monitor is down". Still: do not treat "no alerts" as evidence on its own.
 */
public final class HealthMonitorApplication {
    /** The logger name and the Loki {@code app} label; matches the artifactId. */
    private static final String APP_NAME = "health-monitor";
    /**
     * The {@code stream_type} the heartbeat is published on — deliberately not {@code app}.
     *
     * <p>A beat every interval forever is thousands of lines a day that say nothing happened, and the
     * app stream is what {@code apps/log-analyzer} triages for errors. Its own stream costs that
     * reader nothing and lets {@code apps/liveness-analyzer} select it with
     * {@code {stream_type="heartbeat"}}.
     */
    private static final String HEARTBEAT_STREAM = "heartbeat";

    public static void main(String[] args) {
        Env env;
        try {
            env = HealthMonitorEnvs.load();
        } catch (Exception exception) {
            // Before the logger exists there is nowhere else for this to go.
            System.err.println("health-monitor: " + exception.getMessage());
            System.exit(1);
            return;
        }

        Logger logger = loggerFrom(env);
        List<HealthTarget> targets;
        try {
            targets = HealthTarget.parse(env.get(HealthMonitorEnvs.HEALTH_MONITOR_TARGETS));
        } catch (IllegalArgumentException exception) {
            // Fatal. A monitor that silently drops a malformed target watches less than it was told
            // to and reports nothing about the difference — and no alerts is what a healthy stack
            // looks like, so there would be no symptom to notice.
            System.err.println("health-monitor: " + exception.getMessage());
            logger.error("The target list could not be read.",
                    Map.of("error", String.valueOf(exception.getMessage())));
            System.exit(2);
            return;
        }

        int timeoutSeconds = env.get(HealthMonitorEnvs.HEALTH_MONITOR_TIMEOUT_SECONDS);
        int intervalSeconds = env.get(HealthMonitorEnvs.HEALTH_MONITOR_INTERVAL_SECONDS);
        describe(targets, intervalSeconds, timeoutSeconds, logger);

        HealthMonitorService service = new HealthMonitorService(
                targets,
                new HealthProbe(Duration.ofSeconds(Math.max(1, timeoutSeconds)), logger),
                env.get(HealthMonitorEnvs.HEALTH_MONITOR_FAILURES_BEFORE_DOWN),
                env.get(HealthMonitorEnvs.HEALTH_MONITOR_SUCCESSES_BEFORE_UP),
                env.get(HealthMonitorEnvs.HEALTH_MONITOR_ALERT_URL),
                logger);

        // A heartbeat on its own Loki stream, so that something else can notice this app stopping.
        // See LoggerSetup.heartbeatChannel for why it is a separate stream and not the app log.
        Optional<ManagedLogger> heartbeat = LoggerSetup.heartbeatChannel(
                HEARTBEAT_STREAM, APP_NAME,
                env.get(HealthMonitorEnvs.LOKI_URL),
                env.get(HealthMonitorEnvs.LOKI_TENANT_ID));
        if (heartbeat.isEmpty()) {
            logger.warn("No LOKI_URL, so no heartbeat is published and nothing can detect this "
                    + "monitor dying. An external check on /health is then the only cover.", Map.of());
        }

        // Three intervals: one missed round is a slow target or a long round, three in a row is the
        // schedule having stopped. Plus a floor, so a one-second interval does not make this hair
        // triggered on ordinary jitter.
        Duration staleAfter = Duration.ofSeconds(Math.max(30L, intervalSeconds * 3L));

        try (HealthMonitorSchedule schedule = new HealthMonitorSchedule(
                service, intervalSeconds, logger, heartbeat.map(Logger.class::cast))) {
            schedule.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                schedule.close();
                // Flushes the heartbeat queue. Without it the last few beats are lost on shutdown,
                // which would look to a dead-man's switch like the process died earlier than it did.
                heartbeat.ifPresent(ManagedLogger::close);
            }, "health-shutdown"));
            var routes = UndertowHttp.routes()
                    .get("/health", exchange -> health(exchange, service, staleAfter))
                    .get("/targets", exchange -> UndertowHttp.sendJson(
                            exchange, 200, service.targetsJson()));
            // Blocks until the process is stopped; the schedule runs behind it.
            UndertowHttp.startAndWait(
                    env.get(HealthMonitorEnvs.SERVER_ADDRESS),
                    env.get(HealthMonitorEnvs.SERVER_PORT),
                    routes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * This app's own health.
     *
     * <p>Still healthy while <em>targets</em> are down — that is a fact about the stack, and reporting
     * it here would have an orchestrator restart the monitor at the exact moment it is doing its job.
     * But <strong>unhealthy when its own rounds have stopped</strong>, because that is a fact about
     * this process, and it is the failure whose whole danger is that it looks like nothing.
     *
     * <p>That distinction is what makes an external check on this endpoint worth anything: a port-open
     * check cannot tell a working monitor from a wedged one, and this can.
     */
    private static void health(HttpServerExchange exchange, HealthMonitorService service,
            Duration staleAfter) throws IOException {
        Map<String, Object> details = new LinkedHashMap<>(service.healthDetails());
        if (service.checksAreCurrent(staleAfter)) {
            details.put("checksCurrent", true);
            UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(details));
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("status", "unhealthy");
        body.put("error", "no health check round has completed in the last "
                + staleAfter.toSeconds() + "s");
        body.put("checksCurrent", false);
        body.putAll(details);
        body.put("engine", "undertow");
        UndertowHttp.sendJson(exchange, 503, body);
    }

    /**
     * Says what it is watching, at startup.
     *
     * <p>An empty target list is the most likely misconfiguration and has no other symptom — a
     * monitor watching nothing and a stack with nothing wrong produce identical output. This is the
     * line that tells them apart.
     */
    private static void describe(List<HealthTarget> targets, int intervalSeconds, int timeoutSeconds,
            Logger logger) {
        if (targets.isEmpty()) {
            logger.warn("No targets are configured, so nothing is being checked. "
                    + "Set HEALTH_MONITOR_TARGETS to a comma-separated name=url list.", Map.of());
            return;
        }
        if (timeoutSeconds >= intervalSeconds) {
            // Not fatal — it still works, just badly — but it is always a mistake, and it shows up
            // as rounds running late rather than as anything that names itself.
            logger.warn("The check timeout is not shorter than the interval, so a dead target is "
                            + "still being waited on when the next round is due.",
                    Map.of("timeoutSeconds", timeoutSeconds, "intervalSeconds", intervalSeconds));
        }
        for (HealthTarget target : targets) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("target", target.name());
            metadata.put("url", target.url().toString());
            logger.info("Watching a health endpoint.", metadata);
        }
    }

    /**
     * The sinks {@code LOGGER} asks for, assembled by the same {@code LoggerSetup} the Spring servers
     * use.
     *
     * <p>{@code loki-with-fallback} is worth more here than almost anywhere: this app's whole output
     * is a handful of lines a year, each one saying something broke, and a Loki outage that swallowed
     * one would swallow the only record of an outage.
     */
    private static Logger loggerFrom(Env env) {
        return LoggerSetup.fromConfiguration(
                APP_NAME,
                env.get(HealthMonitorEnvs.LOGGER),
                env.get(HealthMonitorEnvs.LOKI_URL),
                env.get(HealthMonitorEnvs.LOKI_TENANT_ID));
    }

    private HealthMonitorApplication() {
    }
}
