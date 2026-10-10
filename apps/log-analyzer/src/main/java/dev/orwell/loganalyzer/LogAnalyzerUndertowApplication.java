package dev.orwell.loganalyzer;

import dev.orwell.env.Env;
import dev.orwell.logging.LoggerSetup;
import dev.orwell.logging.ManagedLogger;
import dev.orwell.undertow.UndertowHttp;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class LogAnalyzerUndertowApplication {
    private LogAnalyzerUndertowApplication() {
    }

    static void start(Env env) throws InterruptedException {
        // The Spring engine gets its Logger from LoggerConfiguration; there is no context here, so
        // this path assembles the same sinks from the same LOGGER value by hand. Closing it flushes
        // whatever Loki still has queued.
        try (ManagedLogger logger = LoggerSetup.fromConfiguration(
                "log-analyzer",
                optional(env, LogAnalyzerEnvs.ENV.LOGGER),
                optional(env, LogAnalyzerEnvs.ENV.LOKI_URL),
                optional(env, LogAnalyzerEnvs.ENV.LOKI_TENANT_ID))) {
            start(env, logger);
        }
    }

    /**
     * These three are declared on {@code AppServerEnv} as optional with no default, and
     * {@code Env.get} throws for an optional that was never set — so reading them directly would
     * make an unset {@code LOGGER}, which is the ordinary case, fail the server before it binds.
     * {@code LoggerSetup} takes blank to mean "decide from LOKI_URL", which is the documented
     * default.
     */
    static String optional(Env env, dev.orwell.env.EnvOption<String> option) {
        return env.has(option) ? env.get(option) : "";
    }

    private static void start(Env env, ManagedLogger logger) throws InterruptedException {
        LogAnalyzerService service = LogAnalyzerService.fromEnv(env, logger);
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
