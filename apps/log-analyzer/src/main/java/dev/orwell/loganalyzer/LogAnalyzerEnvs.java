package dev.orwell.loganalyzer;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class LogAnalyzerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);
    public static final EnvOption<Integer> POLL_INTERVAL_SECONDS;
    public static final EnvOption<Integer> LOOKBACK_SECONDS;
    public static final EnvOption<String> GRAFANA_URL;
    public static final EnvOption<String> GRAFANA_API_TOKEN;
    public static final EnvOption<String> GRAFANA_LOKI_DATASOURCE_UID;
    public static final EnvOption<String> LOKI_QUERY;
    public static final EnvOption<Integer> MAX_LOG_LINES;
    public static final EnvOption<Integer> ALERT_COOLDOWN_SECONDS;
    public static final EnvOption<String> ALERT_URL;
    public static final EnvOption<String> AI_API_URL;
    public static final EnvOption<String> AI_API_KEY;
    public static final EnvOption<String> AI_MODEL;
    public static final EnvOption<Integer> AI_TIMEOUT_SECONDS;
    public static final EnvOption<Double> MIN_IMPORTANCE_CONFIDENCE;

    static {
        POLL_INTERVAL_SECONDS = ENV.optional("LOG_ANALYZER_POLL_INTERVAL_SECONDS", EnvType.integer(), 60);
        LOOKBACK_SECONDS = ENV.optional("LOG_ANALYZER_LOOKBACK_SECONDS", EnvType.integer(), 300);
        GRAFANA_URL = ENV.optional("GRAFANA_URL", EnvType.string(), "http://127.0.0.1:3000");
        GRAFANA_API_TOKEN = ENV.optional("GRAFANA_API_TOKEN", EnvType.string(), "");
        GRAFANA_LOKI_DATASOURCE_UID = ENV.optional("GRAFANA_LOKI_DATASOURCE_UID", EnvType.string(), "");
        LOKI_QUERY = ENV.optional("LOKI_QUERY", EnvType.string(),
                "{stream_type=\"app\"} | json | level=\"ERROR\"");
        MAX_LOG_LINES = ENV.optional("LOG_ANALYZER_MAX_LOG_LINES", EnvType.integer(), 120);
        ALERT_COOLDOWN_SECONDS = ENV.optional("LOG_ANALYZER_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 900);
        ALERT_URL = ENV.optional("ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        AI_API_URL = ENV.optional("AI_API_URL", EnvType.string(), "https://api.openai.com/v1/chat/completions");
        AI_API_KEY = ENV.optional("AI_API_KEY", EnvType.string(), "");
        AI_MODEL = ENV.optional("AI_MODEL", EnvType.string(), "gpt-4o-mini");
        AI_TIMEOUT_SECONDS = ENV.optional("AI_TIMEOUT_SECONDS", EnvType.integer(), 30);
        MIN_IMPORTANCE_CONFIDENCE = ENV.optional("MIN_IMPORTANCE_CONFIDENCE", EnvType.of(Double.class, "double", Double::parseDouble), 0.7);
        ENV.property("loganalyzer.poll-interval-seconds", POLL_INTERVAL_SECONDS);
        ENV.property("loganalyzer.lookback-seconds", LOOKBACK_SECONDS);
        ENV.property("loganalyzer.max-log-lines", MAX_LOG_LINES);
        ENV.property("loganalyzer.grafana-url", GRAFANA_URL);
        ENV.property("loganalyzer.grafana-api-token", GRAFANA_API_TOKEN);
        ENV.property("loganalyzer.grafana-loki-datasource-uid", GRAFANA_LOKI_DATASOURCE_UID);
        ENV.property("loganalyzer.loki-query", LOKI_QUERY);
        ENV.property("loganalyzer.alert-url", ALERT_URL);
        ENV.property("loganalyzer.ai-api-url", AI_API_URL);
        ENV.property("loganalyzer.ai-api-key", AI_API_KEY);
        ENV.property("loganalyzer.ai-model", AI_MODEL);
        ENV.property("loganalyzer.ai-timeout-seconds", AI_TIMEOUT_SECONDS);
        ENV.property("loganalyzer.min-importance-confidence", MIN_IMPORTANCE_CONFIDENCE);
        ENV.property("loganalyzer.alert-cooldown-seconds", ALERT_COOLDOWN_SECONDS);
    }

    private LogAnalyzerEnvs() {
    }
}
