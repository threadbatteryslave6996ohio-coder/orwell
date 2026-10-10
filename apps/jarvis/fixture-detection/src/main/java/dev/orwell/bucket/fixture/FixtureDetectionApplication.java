package dev.orwell.bucket.fixture;

import dev.orwell.bucket.frame.client.FrameStreamClient;
import dev.orwell.bucket.frame.client.FrameStreamOptions;
import dev.orwell.env.Env;
import dev.orwell.logging.Logger;
import dev.orwell.logging.LoggerSetup;
import dev.orwell.undertow.UndertowHttp;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fixture detection: is the lamp on, is the burner lit.
 *
 * <p>A watcher, not a service anything calls. It takes frames from {@code jarvis-frame-client},
 * measures a configured region of each one, and writes a log line — and optionally an alert — when a
 * fixture's state changes.
 *
 * <p><strong>Why photometry and not a vision model.</strong> For a camera that does not move, "is
 * this fixture emitting light" is a measurement, not a judgement: the pixels where the lamp is get
 * brighter, and a threshold answers it. That is deterministic, unit-testable against synthetic
 * frames, costs microseconds, needs no GPU, and cannot hallucinate. A vision model asked the same
 * question is slower by four orders of magnitude, needs calibration you cannot inspect, and will
 * occasionally say the lamp is on because there is a window in shot. Keep the model for the open
 * question — "is anything unusual happening here" — and measure the closed one.
 *
 * <p>What it does not do: it has no idea what a lamp <em>is</em>. It knows that a rectangle you drew
 * got brighter than a number you chose. Point it at the wrong rectangle and it will confidently
 * report the state of a wall.
 *
 * <pre>
 * GET /health                      counters, and every fixture's current state
 * GET /fixtures                    per fixture: live value, thresholds, recent readings
 * GET /fixtures/{name}/preview     the latest frame with the regions drawn on it — calibrate with this
 * </pre>
 */
public final class FixtureDetectionApplication {
    /** The logger name and the Loki {@code app} label; matches the artifactId. */
    private static final String APP_NAME = "jarvis-fixture-detection";

    public static void main(String[] args) {
        Env env;
        try {
            env = FixtureDetectionEnvs.load();
        } catch (Exception exception) {
            // Before the logger exists there is nowhere else for this to go.
            System.err.println("fixture-detection: " + exception.getMessage());
            System.exit(1);
            return;
        }

        Logger logger = loggerFrom(env);
        List<Fixture> fixtures;
        try {
            fixtures = FixtureDefinitions.load(
                    env.get(FixtureDetectionEnvs.FIXTURE_DETECTION_DEFINITIONS),
                    env.get(FixtureDetectionEnvs.FIXTURE_DETECTION_DEFINITIONS_FILE));
        } catch (IllegalArgumentException exception) {
            // Fatal, and loudly. A detector that starts with a broken fixture list watches nothing
            // and looks exactly like a working one: silence is what a correct lamp detector produces
            // most of the time, so there is no symptom to notice later.
            System.err.println("fixture-detection: " + exception.getMessage());
            logger.error("The fixture definitions could not be read.",
                    Map.of("error", String.valueOf(exception.getMessage())));
            System.exit(2);
            return;
        }

        describe(fixtures, logger);
        FixtureDetectionService service =
                new FixtureDetectionService(fixtures, env.get(FixtureDetectionEnvs.FIXTURE_DETECTION_ALERT_URL), logger);

        String source = env.get(FixtureDetectionEnvs.FIXTURE_DETECTION_SOURCE);
        FrameStreamOptions options = FrameStreamOptions.of(
                        env.get(FixtureDetectionEnvs.FIXTURE_DETECTION_HUB_URL),
                        env.get(FixtureDetectionEnvs.FIXTURE_DETECTION_SUBSCRIPTION))
                .withSource(source == null || source.isBlank() ? null : source);

        try (FrameStreamClient frames = new FrameStreamClient(options, service::onFrame, logger)) {
            frames.start();
            Runtime.getRuntime().addShutdownHook(
                    new Thread(frames::close, "fixture-detection-shutdown"));
            var routes = UndertowHttp.routes()
                    .get("/health", exchange -> UndertowHttp.sendJson(
                            exchange, 200, UndertowHttp.health(health(frames, service))))
                    .get("/fixtures", exchange -> UndertowHttp.sendJson(
                            exchange, 200, fixturesJson(service)))
                    .get("/fixtures/{name}/preview", exchange -> preview(exchange, service));
            // Blocks until the process is stopped; the stream client runs behind it.
            UndertowHttp.startAndWait(
                    env.get(FixtureDetectionEnvs.SERVER_ADDRESS),
                    env.get(FixtureDetectionEnvs.SERVER_PORT),
                    routes);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Says what it is watching at startup.
     *
     * <p>Not decoration: an empty fixture list is the most likely misconfiguration and produces no
     * other symptom at all. This is the line that distinguishes "nothing has happened" from "nothing
     * is being watched".
     */
    private static void describe(List<Fixture> fixtures, Logger logger) {
        if (fixtures.isEmpty()) {
            logger.warn("No fixtures are configured, so nothing will be detected. Set "
                    + "FIXTURE_DETECTION_DEFINITIONS or FIXTURE_DETECTION_DEFINITIONS_FILE.",
                    Map.of());
            return;
        }
        for (Fixture fixture : fixtures) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("fixture", fixture.name());
            metadata.put("source", fixture.source() == null ? "*" : fixture.source());
            metadata.put("metric", fixture.metric().name());
            metadata.put("onAbove", fixture.onAbove());
            metadata.put("offBelow", fixture.offBelow());
            metadata.put("hasReference", fixture.reference() != null);
            logger.info("Watching a fixture.", metadata);
        }
    }

    /** Both halves: whether frames are arriving, and what has been made of them. */
    private static Map<String, Object> health(FrameStreamClient frames,
            FixtureDetectionService service) {
        Map<String, Object> details = new LinkedHashMap<>(frames.healthDetails());
        details.putAll(service.healthDetails());
        return details;
    }

    private static Map<String, Object> fixturesJson(FixtureDetectionService service) {
        List<Map<String, Object>> fixtures = new ArrayList<>();
        for (FixtureWatch watch : service.watches()) {
            fixtures.add(watch.asJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("fixtures", fixtures);
        return body;
    }

    /**
     * The latest frame with the fixture's regions drawn on it.
     *
     * <p>An image rather than JSON because the question it answers is visual: is the green box on the
     * lamp. See {@link FixturePreview}.
     */
    private static void preview(HttpServerExchange exchange, FixtureDetectionService service)
            throws IOException {
        var names = exchange.getQueryParameters().get("name");
        String name = names == null || names.isEmpty() ? null : names.getFirst();
        Optional<FixtureWatch> watch = name == null ? Optional.empty() : service.watch(name);
        if (watch.isEmpty()) {
            UndertowHttp.sendError(exchange, 404, "no fixture called '" + name + "'");
            return;
        }
        Optional<byte[]> frame = service.latestFrame(watch.get().fixture().source());
        if (frame.isEmpty()) {
            UndertowHttp.sendError(exchange, 503,
                    "no frame has arrived from that source yet");
            return;
        }
        Optional<byte[]> rendered = FixturePreview.render(frame.get(), watch.get());
        if (rendered.isEmpty()) {
            UndertowHttp.sendError(exchange, 415,
                    "the latest frame from that source is not a decodable image");
            return;
        }

        byte[] body = rendered.get();
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "image/jpeg");
        exchange.getResponseHeaders().put(Headers.CONTENT_LENGTH, Integer.toString(body.length));
        // Calibration means reloading this while flipping a switch, so it must never be cached.
        exchange.getResponseHeaders().put(Headers.CACHE_CONTROL, "no-store");
        exchange.getResponseSender().send(ByteBuffer.wrap(body));
    }

    /**
     * The sinks {@code LOGGER} asks for, assembled by the same {@code LoggerSetup} the Spring servers
     * use — so this process answers to the same configuration as the rest of the repo even though
     * there is no Spring context here to build the bean.
     *
     * <p>Worth considering {@code loki-with-fallback} here: a transition is reported once and never
     * again, so a log line lost to a Loki outage is a state change nobody can recover.
     */
    private static Logger loggerFrom(Env env) {
        return LoggerSetup.fromConfiguration(
                APP_NAME,
                env.get(FixtureDetectionEnvs.LOGGER),
                env.get(FixtureDetectionEnvs.LOKI_URL),
                env.get(FixtureDetectionEnvs.LOKI_TENANT_ID));
    }

    private FixtureDetectionApplication() {
    }
}
