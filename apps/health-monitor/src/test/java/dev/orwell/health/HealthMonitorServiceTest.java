package dev.orwell.health;

import dev.orwell.testing.http.JsonStubServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** A round end to end: real endpoints, real transitions, real alerts. */
class HealthMonitorServiceTest {
    private JsonStubServer stack;
    private JsonStubServer alerting;
    private final List<String> logged = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        stack = JsonStubServer.start();
        alerting = JsonStubServer.start();
        logged.clear();
    }

    @AfterEach
    void stop() {
        stack.close();
        alerting.close();
    }

    @Test
    void checksEveryTargetInARound() {
        stack.stub("/a/health", 200, "{\"status\":\"healthy\"}");
        stack.stub("/b/health", 200, "{\"status\":\"healthy\"}");
        HealthMonitorService service = service(null, "a=/a/health", "b=/b/health");

        service.checkAll();

        assertThat(service.downCount()).isZero();
        assertThat(service.healthDetails())
                .containsEntry("targets", 2)
                .containsEntry("checksTotal", 2L)
                .containsEntry("roundsTotal", 1L);
    }

    @Test
    void reportsAnOutageOnceTheFailureIsConfirmed() {
        stack.stub("/a/health", 500, "{}");
        HealthMonitorService service = service(null, "a=/a/health");

        service.checkAll();
        assertThat(service.downCount()).isZero();   // one failure is not an outage

        service.checkAll();

        assertThat(service.downCount()).isEqualTo(1);
        assertThat(logged).contains("A health endpoint stopped answering.");
    }

    @Test
    void postsAnAlertWhenATargetGoesDownAndAgainWhenItRecovers() throws Exception {
        AtomicReference<String> received = alerting.stub("/alerts", 200, "{\"ok\":true}");
        // Flips from broken to healthy on demand, so one service can drive both transitions.
        AtomicInteger status = new AtomicInteger(500);
        stack.on("/a/health", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();

        assertThat(received.get())
                .contains("\"event\":\"health_check_failed\"")
                .contains("\"severity\":\"critical\"")
                .contains("\"target\":\"a\"")
                .contains("\"state\":\"down\"");
        assertThat(received.get()).contains("is not answering its health endpoint");

        status.set(200);
        service.checkAll();

        assertThat(received.get())
                .contains("\"event\":\"health_check_recovered\"")
                .contains("\"severity\":\"info\"")
                .contains("\"state\":\"up\"");
        assertThat(service.healthDetails()).containsEntry("alertsSentTotal", 2L);
    }

    /**
     * A target seen healthy for the first time is logged and not alerted. Without this, restarting
     * the monitor fires one "is healthy again" per target — a fourteen-message burst on the stack in
     * docker-compose.all-services.yml, on a channel whose value is that a message means something.
     */
    @Test
    void doesNotAlertWhenATargetIsHealthyOnTheFirstCheck() throws Exception {
        AtomicReference<String> received = alerting.stub("/alerts", 200, "{\"ok\":true}");
        stack.stub("/a/health", 200, "{\"status\":\"healthy\"}");
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();

        assertThat(received.get()).isNull();
        assertThat(service.healthDetails()).containsEntry("alertsSentTotal", 0L);
        // Logged, though — the state is not being hidden, only kept off the alert channel.
        assertThat(logged).contains("A health endpoint answered for the first time.");
    }

    /** Starting up to find something already broken IS worth an alert. */
    @Test
    void doesAlertWhenATargetIsAlreadyDownOnTheFirstChecks() throws Exception {
        AtomicReference<String> received = alerting.stub("/alerts", 200, "{\"ok\":true}");
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts",
                "dead=http://127.0.0.1:1/health");

        service.checkAll();
        service.checkAll();

        assertThat(received.get()).contains("\"event\":\"health_check_failed\"");
    }

    /**
     * The email is the point of alerting at all, so "alerting answered 200" is not the success
     * condition — {@code emailSent} is. Counted apart, because the two diverging is the only signal
     * that the mail path has quietly stopped working.
     */
    @Test
    void countsTheEmailSeparatelyFromTheAlert() throws Exception {
        alerting.stub("/alerts", 200,
                "{\"success\":true,\"emailEnabled\":true,\"emailSent\":true,\"emailError\":null}");
        stack.stub("/a/health", 500, "{}");
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();

        assertThat(service.healthDetails())
                .containsEntry("alertsSentTotal", 1L)
                .containsEntry("emailsSentTotal", 1L)
                .containsEntry("lastAlertProblem", null);
    }

    /**
     * The failure this exists to catch: alerting accepts the alert, sends no mail because it is not
     * configured to, and answers 200. Counting that as success is how a monitor reports cover it does
     * not have.
     */
    @Test
    void warnsWhenAlertingTookTheAlertButSentNoEmail() throws Exception {
        alerting.stub("/alerts", 200,
                "{\"success\":true,\"emailEnabled\":false,\"emailSent\":false,\"emailError\":null}");
        stack.stub("/a/health", 500, "{}");
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();

        assertThat(service.healthDetails())
                .containsEntry("alertsSentTotal", 1L)
                .containsEntry("emailsSentTotal", 0L);
        assertThat(service.healthDetails().get("lastAlertProblem").toString())
                .contains("ALERT_EMAIL_ENABLED");
        assertThat(logged).contains("A health transition reached alerting but produced no email.");
    }

    /** An SMTP failure at the far end is named, so the fix is not a guessing game. */
    @Test
    void reportsTheReasonAlertingCouldNotSendTheEmail() throws Exception {
        alerting.stub("/alerts", 200,
                "{\"success\":true,\"emailEnabled\":true,\"emailSent\":false,"
                        + "\"emailError\":\"Couldn't connect to host, port: smtp, 587\"}");
        stack.stub("/a/health", 500, "{}");
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();

        assertThat(service.healthDetails().get("lastAlertProblem").toString())
                .contains("Couldn't connect to host");
        assertThat(service.healthDetails()).containsEntry("emailsSentTotal", 0L);
    }

    /** A recovery emails too — otherwise whoever was woken has no way to know it is over. */
    @Test
    void emailsTheRecoveryAsWellAsTheOutage() throws Exception {
        alerting.stub("/alerts", 200,
                "{\"success\":true,\"emailEnabled\":true,\"emailSent\":true}");
        AtomicInteger status = new AtomicInteger(500);
        stack.on("/a/health", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        HealthMonitorService service = service(alerting.baseUrl() + "/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();
        status.set(200);
        service.checkAll();

        assertThat(service.healthDetails()).containsEntry("emailsSentTotal", 2L);
    }

    /** One dead target must not stop the others being checked. */
    @Test
    void keepsCheckingTheRestWhenOneTargetIsUnreachable() {
        stack.stub("/b/health", 200, "{\"status\":\"healthy\"}");
        HealthMonitorService service = service(null,
                "dead=http://127.0.0.1:1/health", "b=/b/health");

        service.checkAll();
        service.checkAll();

        assertThat(service.downCount()).isEqualTo(1);
        Map<String, Object> details = service.healthDetails();
        @SuppressWarnings("unchecked")
        Map<String, Object> states = (Map<String, Object>) details.get("states");
        assertThat(states).containsKeys("dead", "b");
        @SuppressWarnings("unchecked")
        Map<String, Object> healthy = (Map<String, Object>) states.get("b");
        assertThat(healthy).containsEntry("state", "up");
    }

    /** Alerting being down must not stop the monitoring, and the log keeps the record. */
    @Test
    void keepsMonitoringWhenTheAlertCannotBeDelivered() {
        stack.stub("/a/health", 500, "{}");
        HealthMonitorService service = service("http://127.0.0.1:1/alerts", "a=/a/health");

        service.checkAll();
        service.checkAll();

        assertThat(service.downCount()).isEqualTo(1);
        // The outage line is written before the alert is attempted, so it survives alerting failing.
        assertThat(logged).contains("A health endpoint stopped answering.");
        assertThat(logged).anyMatch(message -> message.contains("could not be delivered"));
        assertThat(service.healthDetails()).containsEntry("alertsSentTotal", 0L);
    }

    /**
     * Its own health stays healthy while targets are down — otherwise Docker restarts the monitor
     * exactly when the thing it monitors breaks.
     */
    @Test
    void reportsItsOwnHealthSeparatelyFromTheStacks() {
        stack.stub("/a/health", 500, "{}");
        HealthMonitorService service = service(null, "a=/a/health");

        service.checkAll();
        service.checkAll();

        Map<String, Object> details = service.healthDetails();
        assertThat(details).containsEntry("targetsDown", 1L);
        // No "status" key of its own: UndertowHttp.health stamps healthy around these details, and
        // that is about this process, not about the stack.
        assertThat(details).doesNotContainKey("status");
    }

    @Test
    void servesEverythingKnownAboutEveryTarget() {
        stack.stub("/a/health", 200, "{\"status\":\"healthy\"}");
        HealthMonitorService service = service(null, "a=/a/health");

        service.checkAll();

        Map<String, Object> body = service.targetsJson();
        assertThat(body).containsEntry("success", true).containsEntry("targetsDown", 0L);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) body.get("targets");
        assertThat(targets).singleElement()
                .satisfies(target -> {
                    assertThat(target).containsEntry("name", "a").containsEntry("state", "up");
                    assertThat(target.get("url").toString()).endsWith("/a/health");
                });
    }

    /** Target urls are relative to the stub server here; absolute ones are passed through. */
    private HealthMonitorService service(String alertUrl, String... targets) {
        List<HealthTarget> parsed = new ArrayList<>();
        for (String target : targets) {
            int equals = target.indexOf('=');
            String name = target.substring(0, equals);
            String path = target.substring(equals + 1);
            parsed.add(new HealthTarget(name,
                    URI.create(path.startsWith("http") ? path : stack.baseUrl() + path)));
        }
        return new HealthMonitorService(
                parsed,
                new HealthProbe(Duration.ofSeconds(2), entry -> { }),
                2, 1, alertUrl,
                entry -> logged.add(entry.message()));
    }
}
