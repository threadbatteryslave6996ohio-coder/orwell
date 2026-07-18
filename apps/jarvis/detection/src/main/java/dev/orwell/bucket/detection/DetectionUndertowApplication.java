package dev.orwell.bucket.detection;

import dev.orwell.env.Env;
import dev.orwell.undertow.UndertowHttp;

final class DetectionUndertowApplication {
    private static final long MAX_DETECTION_REQUEST_BYTES = 16L * 1024 * 1024;

    private DetectionUndertowApplication() {
    }

    static void start(Env env) throws InterruptedException {
        DetectionEndpoint endpoint = new DetectionEndpoint(DetectionService.fromEnv(env));
        var routes = UndertowHttp.routes()
                .get("/health", exchange -> {
                    UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(endpoint.healthDetails()));
                })
                .post("/detect", UndertowHttp.jsonObject(MAX_DETECTION_REQUEST_BYTES, endpoint::detect));
        UndertowHttp.startAndWait(
                env.get(DetectionEnvs.ENV.SERVER_ADDRESS), env.get(DetectionEnvs.ENV.SERVER_PORT), routes);
    }
}
