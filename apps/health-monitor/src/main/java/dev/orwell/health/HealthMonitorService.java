package dev.orwell.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.Logger;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks every target once a round, and says something when one of them changes.
 *
 * <p><strong>Targets are checked in parallel.</strong> Sequentially, one target that has stopped
 * answering delays every target after it by the full timeout — so with eight services and a
 * five-second timeout, one dead service can push the round past its own interval and the whole stack
 * gets checked late. Concurrency here is not about speed; it is about one broken thing not blinding
 * you to the others.
 *
 * <p>It reports transitions only, in both directions. <strong>Recovery is alerted too</strong>,
 * which is not decoration: a monitor that only reports failure leaves whoever it woke with no way to
 * know it is over except by asking it. The exception is a target seen healthy for the very first
 * time, which is logged and not alerted — see {@link #report}.
 */
public class HealthMonitorService {
    private static final Duration ALERT_TIMEOUT = Duration.ofSeconds(15);
    /** Concurrent checks. Above this, a large target list would open more sockets than it is worth. */
    private static final int MAX_CONCURRENCY = 8;

    private final List<TargetWatch> watches;
    private final HealthProbe probe;
    private final String alertUrl;
    private final Logger logger;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient alertClient;
    private final ExecutorService checkers;
    private final AtomicLong roundsTotal = new AtomicLong();
    private final AtomicLong checksTotal = new AtomicLong();
    private final AtomicLong alertsSentTotal = new AtomicLong();
    private final AtomicLong emailsSentTotal = new AtomicLong();
    private final AtomicReference<String> lastAlertProblem = new AtomicReference<>();
    private final Instant startedAt = Instant.now();
    private volatile Instant lastRoundAt;

    public HealthMonitorService(List<HealthTarget> targets, HealthProbe probe, int failuresBeforeDown,
            int successesBeforeUp, String alertUrl, Logger logger) {
        Objects.requireNonNull(targets, "targets");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.alertUrl = alertUrl == null || alertUrl.isBlank() ? null : alertUrl.trim();
        this.watches = targets.stream()
                .map(target -> new TargetWatch(target, failuresBeforeDown, successesBeforeUp))
                .toList();
        this.alertClient = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), logger);
        this.checkers = Executors.newFixedThreadPool(
                Math.max(1, Math.min(MAX_CONCURRENCY, Math.max(1, targets.size()))),
                runnable -> {
                    Thread thread = new Thread(runnable, "health-check");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    public List<TargetWatch> watches() {
        return watches;
    }

    public long downCount() {
        return watches.stream().filter(TargetWatch::isDown).count();
    }

    /** Checks every target once. Never throws — a round that fails must not stop the schedule. */
    public void checkAll() {
        Instant at = Instant.now();
        List<Future<?>> pending = new ArrayList<>(watches.size());
        for (TargetWatch watch : watches) {
            pending.add(checkers.submit(() -> checkOne(watch, at)));
        }
        for (Future<?> future : pending) {
            try {
                future.get();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                // checkOne swallows its own failures, so this is a task that died some other way.
                logger.error("A health check round failed unexpectedly.",
                        Map.of("error", exception.toString()));
            }
        }
        roundsTotal.incrementAndGet();
        lastRoundAt = at;
    }

    private void checkOne(TargetWatch watch, Instant at) {
        try {
            ProbeResult result = probe.check(watch.target());
            checksTotal.incrementAndGet();
            watch.record(result, at).ifPresent(this::report);
        } catch (RuntimeException exception) {
            // One target must never take the round down with it.
            logger.error("Could not check a target.", Map.of(
                    "target", watch.target().name(), "error", exception.toString()));
        }
    }

    /**
     * Logs every transition, and alerts on the ones that are news.
     *
     * <p><strong>A target seen healthy for the first time is not alerted on</strong>, only logged.
     * The first confirmed check of every target is a transition out of {@code UNKNOWN}, so without
     * this every restart of this process would fire one "is healthy again" alert per target — a
     * fourteen-message burst, on a channel whose whole value is that a message from it means
     * something. Nothing is lost: the state is on {@code /targets} and the line is in the log.
     *
     * <p>{@code UNKNOWN -> DOWN} <em>is</em> alerted, because starting up to find something already
     * broken is exactly what somebody needs telling.
     */
    private void report(TargetWatch.StateChange change) {
        Map<String, Object> metadata = new LinkedHashMap<>(change.asJson());
        boolean firstSighting = change.from() == TargetWatch.State.UNKNOWN;
        if (change.isOutage()) {
            // ERROR, because this is the line somebody greps for and the one an alerting rule on
            // the log stream would key off. The healthy cases are INFO: they are good news.
            logger.error("A health endpoint stopped answering.", metadata);
        } else if (firstSighting) {
            logger.info("A health endpoint answered for the first time.", metadata);
        } else {
            logger.info("A health endpoint is answering again.", metadata);
        }
        if (alertUrl != null && !(firstSighting && !change.isOutage())) {
            sendAlert(change);
        }
    }

    private void sendAlert(TargetWatch.StateChange change) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("event", change.isOutage() ? "health_check_failed" : "health_check_recovered");
        alert.put("source", "health-monitor");
        alert.put("severity", change.isOutage() ? "critical" : "info");
        alert.put("title", change.isOutage()
                ? change.target().name() + " is not answering its health endpoint"
                : change.target().name() + " is healthy again");
        alert.put("message", message(change));
        alert.putAll(change.asJson());
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                    .timeout(ALERT_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(alert)))
                    .build();
            HttpResponse<String> response =
                    alertClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                alertsSentTotal.incrementAndGet();
                checkAnEmailWentOut(change, response.body());
                return;
            }
            alertFailed(change, "alerting answered " + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            alertFailed(change, "interrupted");
        } catch (Exception exception) {
            alertFailed(change, String.valueOf(exception.getMessage()));
        }
    }

    private static String message(TargetWatch.StateChange change) {
        if (change.isOutage()) {
            return "%s (%s) failed its health check: %s".formatted(
                    change.target().name(), change.target().url(), change.result().detail());
        }
        String forHowLong = change.downFor() == null
                ? ""
                : " after %ds down".formatted(change.downFor().toSeconds());
        return "%s (%s) is answering again%s.".formatted(
                change.target().name(), change.target().url(), forHowLong);
    }

    /**
     * Reads what {@code apps/alerting} says it did with the alert.
     *
     * <p><strong>A 200 from alerting does not mean an email was sent.</strong> That service accepts
     * every alert, records it, and only mails it when {@code ALERT_EMAIL_ENABLED} is on, an
     * {@code ALERT_EMAIL_TO} is set and SMTP is configured — otherwise it answers 200 with
     * {@code emailSent: false} and carries on. Treating the status code as success is how a monitor
     * ends up reporting that it alerted somebody for months while no mail ever left the building,
     * which is a worse failure than not alerting at all: it looks like cover.
     *
     * <p>So the body is read, the email is counted separately from the alert, and a delivery that
     * produced no email is a WARN naming which of alerting's settings is missing. It is not an
     * error here — the alert did arrive, and whether it should become mail is that service's
     * decision, not this one's.
     */
    private void checkAnEmailWentOut(TargetWatch.StateChange change, String body) {
        try {
            JsonNode answer = mapper.readTree(body == null ? "" : body);
            if (answer == null || !answer.isObject()) {
                return;
            }
            if (answer.path("emailSent").asBoolean(false)) {
                emailsSentTotal.incrementAndGet();
                lastAlertProblem.set(null);
                return;
            }
            String problem = answer.hasNonNull("emailError") && !answer.get("emailError").asText().isBlank()
                    ? "alerting could not send the email: " + answer.get("emailError").asText()
                    : answer.path("emailEnabled").asBoolean(false)
                            ? "alerting has email enabled but sent none: check ALERT_EMAIL_TO and the "
                                    + "SMTP_* settings on that service"
                            // ASCII only: this string reaches a terminal through ConsoleLogger, and
                            // an em dash renders as "?" wherever the charset is not UTF-8.
                            : "alerting accepted the alert but email is off there: set "
                                    + "ALERT_EMAIL_ENABLED=true and ALERT_EMAIL_TO";
            lastAlertProblem.set(problem);
            logger.warn("A health transition reached alerting but produced no email.", Map.of(
                    "target", change.target().name(),
                    "state", change.to().label(),
                    "reason", problem));
        } catch (Exception exception) {
            // An alerting service that answers something unparseable is not worth failing over; the
            // alert was accepted, which is the part this app is responsible for.
            lastAlertProblem.set("could not read alerting's answer: " + exception.getMessage());
        }
    }

    /**
     * A failed alert is logged, not retried.
     *
     * <p>Worth stating plainly, because this is the case where it hurts most: if alerting is down
     * <em>and</em> a service goes down, nobody is told by this app. The log line above is the record,
     * and it is why the outage is logged before the alert is attempted rather than after.
     */
    private void alertFailed(TargetWatch.StateChange change, String reason) {
        lastAlertProblem.set(reason);
        logger.warn("A health transition could not be delivered to alerting.", Map.of(
                "target", change.target().name(),
                "state", change.to().label(),
                "reason", String.valueOf(reason)));
    }

    /**
     * Whether a round has completed recently enough for this app to be believed.
     *
     * <p>This is the one thing about the monitor that its own liveness does not tell you. The failure
     * that matters is not the process dying — it is the process staying up with its schedule stopped:
     * the port keeps answering, {@code /health} keeps saying healthy, no checks run, and the absence of
     * alerts reads as a healthy stack. So the answer to "is this app working" is "did it finish a round
     * lately", not "is it listening".
     *
     * <p>Before the first round this is judged against startup instead, because a scheduler that never
     * started at all has the same symptom — {@code lastRoundAt} null forever — and treating null as
     * fine would make exactly that case invisible.
     */
    public boolean checksAreCurrent(Duration allowed) {
        Instant last = lastRoundAt;
        Instant since = last == null ? startedAt : last;
        return Duration.between(since, Instant.now()).compareTo(allowed) <= 0;
    }

    /**
     * This app's own health, which is about <em>this app</em>.
     *
     * <p><strong>It stays healthy while targets are down</strong>, and that is deliberate rather than
     * an oversight. Its own {@code /health} is what the container orchestrator restarts it on, and
     * reporting unhealthy because something it watches is unhealthy would have Docker restart the
     * monitor every time the thing it is monitoring breaks — losing the monitor at the exact moment
     * it is doing its job. What is wrong with the stack is in {@code targetsDown} and on
     * {@code /targets}; what is wrong with the monitor is the status.
     */
    public Map<String, Object> healthDetails() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("targets", watches.size());
        details.put("targetsDown", downCount());
        details.put("roundsTotal", roundsTotal.get());
        details.put("checksTotal", checksTotal.get());
        details.put("alertsSentTotal", alertsSentTotal.get());
        // Counted apart from alertsSentTotal on purpose: alerting accepts an alert whether or not it
        // mails it, so these two diverging is the signal that the email path is not actually working.
        details.put("emailsSentTotal", emailsSentTotal.get());
        details.put("alertingEnabled", alertUrl != null);
        details.put("lastAlertProblem", lastAlertProblem.get());
        details.put("lastRoundAt", lastRoundAt == null ? null : lastRoundAt.toString());
        details.put("startedAt", startedAt.toString());
        Map<String, Object> states = new LinkedHashMap<>();
        for (TargetWatch watch : watches) {
            states.put(watch.target().name(), watch.summaryJson());
        }
        details.put("states", states);
        return details;
    }

    /** Everything known about every target, for {@code GET /targets}. */
    public Map<String, Object> targetsJson() {
        List<Map<String, Object>> targets = new ArrayList<>(watches.size());
        for (TargetWatch watch : watches) {
            targets.add(watch.asJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("targets", targets);
        body.put("targetsDown", downCount());
        body.put("lastRoundAt", lastRoundAt == null ? null : lastRoundAt.toString());
        return body;
    }

    public void close() {
        checkers.shutdownNow();
    }
}
