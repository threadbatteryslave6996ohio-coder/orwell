package dev.orwell.alerting;

import dev.orwell.env.Env;
import dev.orwell.undertow.UndertowHttp;

final class AlertUndertowApplication {
    private static final long MAX_ALERT_BYTES = 1024 * 1024;

    private AlertUndertowApplication() {
    }

    static void start(Env env) throws Exception {
        AlertEndpoint endpoint = new AlertEndpoint(AlertService.fromEnv(env));
        var routes = UndertowHttp.routes()
                .get("/health", exchange -> UndertowHttp.sendJson(
                        exchange, 200, UndertowHttp.health(endpoint.healthDetails())))
                .post("/alerts", UndertowHttp.jsonObject(MAX_ALERT_BYTES, endpoint::alert));
        UndertowHttp.startAndWait(
                env.get(AlertEnvs.ENV.SERVER_ADDRESS), env.get(AlertEnvs.ENV.SERVER_PORT), routes);
    }
}
