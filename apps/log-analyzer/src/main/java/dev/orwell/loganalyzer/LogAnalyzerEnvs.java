package dev.orwell.loganalyzer;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.time.Duration;
import java.util.Map;

public final class LogAnalyzerEnvs {
    public static final EnvOption<String> SERVER_HOST;
    public static final EnvOption<Integer> SERVER_PORT;
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
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        SERVER_HOST = builder.optional("LOG_ANALYZER_SERVER_HOST", EnvType.string(), "127.0.0.1");
        SERVER_PORT = builder.optional("LOG_ANALYZER_SERVER_PORT", EnvType.integer(), 9010);
        POLL_INTERVAL_SECONDS = builder.optional("LOG_ANALYZER_POLL_INTERVAL_SECONDS", EnvType.integer(), 60);
        LOOKBACK_SECONDS = builder.optional("LOG_ANALYZER_LOOKBACK_SECONDS", EnvType.integer(), 300);
        GRAFANA_URL = builder.optional("GRAFANA_URL", EnvType.string(), "http://127.0.0.1:3000");
        GRAFANA_API_TOKEN = builder.optional("GRAFANA_API_TOKEN", EnvType.string(), "");
        GRAFANA_LOKI_DATASOURCE_UID = builder.optional("GRAFANA_LOKI_DATASOURCE_UID", EnvType.string(), "");
        LOKI_QUERY = builder.optional("LOKI_QUERY", EnvType.string(), "{} |~ \"(?i)(error|exception|fatal|panic)\"");
        MAX_LOG_LINES = builder.optional("LOG_ANALYZER_MAX_LOG_LINES", EnvType.integer(), 120);
        ALERT_COOLDOWN_SECONDS = builder.optional("LOG_ANALYZER_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 900);
        ALERT_URL = builder.optional("ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        AI_API_URL = builder.optional("AI_API_URL", EnvType.string(), "https://api.openai.com/v1/chat/completions");
        AI_API_KEY = builder.optional("AI_API_KEY", EnvType.string(), "");
        AI_MODEL = builder.optional("AI_MODEL", EnvType.string(), "gpt-4o-mini");
        AI_TIMEOUT_SECONDS = builder.optional("AI_TIMEOUT_SECONDS", EnvType.integer(), 30);
        MIN_IMPORTANCE_CONFIDENCE = builder.optional("MIN_IMPORTANCE_CONFIDENCE", EnvType.of(Double.class, "double", Double::parseDouble), 0.7);
        ENV = builder.build();
    }

    private LogAnalyzerEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    static Duration aiTimeout(Env env) {
        return Duration.ofSeconds(env.get(AI_TIMEOUT_SECONDS));
    }
}
