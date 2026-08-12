package dev.orwell.bucket.person;

import dev.orwell.bucket.frame.client.FrameStreamClient;
import dev.orwell.bucket.frame.client.FrameStreamOptions;
import dev.orwell.env.Env;
import dev.orwell.logging.CompositeLogger;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.FailSafeLogger;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LokiLogger;
import dev.orwell.undertow.UndertowHttp;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Person detection: watch the hub's frame stream, run detection on what arrives, alert.
 *
 * <p>It receives frames through {@code jarvis-frame-client} rather than through an endpoint of its
 * own. Producers push each frame once — to the hub — and every service that wants frames watches
 * from there, so adding a watcher costs the producers nothing. That is what deleted the intake
 * half of this app, and with it the whole `SERVER_ENGINE` choice: there are no request routes left
 * to serve on two engines.
 *
 * <p>The port that remains serves exactly one route, {@code GET /health}, reporting both halves of
 * the job: whether the stream is connected and how far it has read (from the client), and what
 * detection has done with it (from the service). A consumer that is up but not connected is the
 * failure this is here to make visible — without it, "running" and "working" look identical.
 */
public final class PersonDetectionApplication {
    public static void main(String[] args) {
        Env env;
        try {
            env = PersonDetectionEnvs.load();
        } catch (Exception exception) {
            // Before the logger exists there is nowhere else for this to go.
            System.err.println("person-detection: " + exception.getMessage());
            System.exit(1);
            return;
        }

        Logger logger = loggerFrom(env);
        PersonDetectionService service = new PersonDetectionService(
                env.get(PersonDetectionEnvs.PERSON_DETECTION_ALERT_URL),
                env.get(PersonDetectionEnvs.PERSON_DETECTION_ALERT_COOLDOWN_SECONDS),
                env.get(PersonDetectionEnvs.PERSON_DETECTION_MIN_CONFIDENCE),
                logger);

        String source = env.get(PersonDetectionEnvs.PERSON_DETECTION_SOURCE);
        FrameStreamOptions options = FrameStreamOptions.of(
                        env.get(PersonDetectionEnvs.PERSON_DETECTION_HUB_URL),
                        env.get(PersonDetectionEnvs.PERSON_DETECTION_SUBSCRIPTION))
                .withSource(source == null || source.isBlank() ? null : source);

        try (FrameStreamClient frames = new FrameStreamClient(options, service::onFrame, logger)) {
            frames.start();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(frames::close, "person-detection-shutdown"));
            var routes = UndertowHttp.routes().get("/health", exchange ->
                    UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(health(frames, service))));
            // Blocks until the process is stopped; the stream client runs behind it.
            UndertowHttp.startAndWait(
                    env.get(PersonDetectionEnvs.SERVER_ADDRESS),
                    env.get(PersonDetectionEnvs.SERVER_PORT),
                    routes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Both halves: the stream client's connection state, and what detection did with the frames. */
    private static Map<String, Object> health(FrameStreamClient frames, PersonDetectionService service) {
        Map<String, Object> details = new LinkedHashMap<>(frames.healthDetails());
        details.putAll(service.healthDetails());
        return details;
    }

    /**
     * Console plus Loki when {@code LOKI_URL} is set, console only when it is not — the same
     * default the Spring servers get from {@code LoggerConfiguration}, built by hand because there
     * is no Spring context here to build it.
     */
    private static Logger loggerFrom(Env env) {
        ConsoleLogger console = new ConsoleLogger("jarvis-person-detection");
        String lokiUrl = env.get(PersonDetectionEnvs.LOKI_URL);
        if (lokiUrl == null || lokiUrl.isBlank()) {
            console.warn("LOKI_URL is not set; detections stay on the console.",
                    Map.of("app", "jarvis-person-detection"));
            return new FailSafeLogger(console);
        }
        LokiLogger loki = new LokiLogger(
                "jarvis-person-detection",
                URI.create(lokiUrl),
                env.get(PersonDetectionEnvs.LOKI_TENANT_ID));
        return new FailSafeLogger(new CompositeLogger(console, loki));
    }

    private PersonDetectionApplication() {
    }
}
