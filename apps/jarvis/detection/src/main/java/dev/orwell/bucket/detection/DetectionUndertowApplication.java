package dev.orwell.bucket.detection;

import dev.orwell.env.Env;
import dev.orwell.undertow.UndertowHttp;

final class DetectionUndertowApplication {
    private static final long MAX_DETECTION_REQUEST_BYTES = 16L * 1024 * 1024;

    private DetectionUndertowApplication() {
    }

    static void start(Env env) throws InterruptedException {
        DetectionEndpoint endpoint =
                new DetectionEndpoint(DetectionService.fromEnv(env), MotionService.fromEnv(env));
        var routes = UndertowHttp.routes()
                .get("/health", exchange -> {
                    UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(endpoint.healthDetails()));
                })
                .post("/detect", UndertowHttp.jsonObject(MAX_DETECTION_REQUEST_BYTES, endpoint::detect))
                .post("/motion", UndertowHttp.jsonObject(MAX_DETECTION_REQUEST_BYTES, endpoint::motion))
                // Registered so the route answers 501 with an explanation rather than a bare 404
                // that reads like a typo. The endpoint itself knows fan-out is unavailable here.
                .post("/frames", UndertowHttp.jsonObject(MAX_DETECTION_REQUEST_BYTES, endpoint::frames));
        UndertowHttp.startAndWait(
                env.get(DetectionEnvs.ENV.SERVER_ADDRESS), env.get(DetectionEnvs.ENV.SERVER_PORT), routes);
    }
}
