package dev.orwell.loganalyzer;

import dev.orwell.env.Env;
import dev.orwell.undertow.UndertowHttp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class LogAnalyzerUndertowApplication {
    private LogAnalyzerUndertowApplication() {
    }

    static void start(Env env) throws InterruptedException {
        LogAnalyzerService service = LogAnalyzerService.fromEnv(env);
        LogAnalyzerEndpoint endpoint = new LogAnalyzerEndpoint(service);
        var routes = UndertowHttp.routes()
                .get("/health", exchange -> {
                    UndertowHttp.sendJson(exchange, 200, UndertowHttp.health(endpoint.healthDetails()));
                })
                .post("/run-once", exchange -> {
                    UndertowHttp.send(exchange, endpoint.runOnce());
                });

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "log-analyzer-poller");
            thread.setDaemon(false);
            return thread;
        });
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        service.pollOnce();
                    } catch (Exception exception) {
                        service.recordRunOnceFailure(exception);
                    }
                },
                0,
                env.get(LogAnalyzerEnvs.POLL_INTERVAL_SECONDS),
                TimeUnit.SECONDS
        );
        Runtime.getRuntime().addShutdownHook(new Thread(scheduler::shutdownNow, "poller-shutdown"));
        try {
            UndertowHttp.startAndWait(
                    env.get(LogAnalyzerEnvs.ENV.SERVER_ADDRESS),
                    env.get(LogAnalyzerEnvs.ENV.SERVER_PORT),
                    routes
            );
        } finally {
            scheduler.shutdownNow();
        }
    }
}
