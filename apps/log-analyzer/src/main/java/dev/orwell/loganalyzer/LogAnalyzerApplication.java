package dev.orwell.loganalyzer;

import dev.orwell.undertow.ServerRuntime;

/**
 * Log-analyzer server. Polls Grafana Loki on a schedule (and on demand via {@code POST /run-once}),
 * classifies error logs with an AI model, and forwards cooldown-gated alerts.
 */
public final class LogAnalyzerApplication {
    public static void main(String[] args) {
        ServerRuntime.runOrExit(
                args,
                LogAnalyzerEnvs.ENV.schema(),
                LogAnalyzerSpringApplication::start,
                LogAnalyzerUndertowApplication::start
        );
    }
}
