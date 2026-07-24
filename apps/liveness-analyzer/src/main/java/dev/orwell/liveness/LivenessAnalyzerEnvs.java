package dev.orwell.liveness;

import dev.orwell.bootstrap.launch.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

/**
 * Environment schema for the liveness analyzer. The three Grafana keys are shared with
 * {@code log-analyzer} on purpose — both read the same Loki through the same datasource proxy.
 */
public final class LivenessAnalyzerEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);
    public static final EnvOption<Integer> CHECK_INTERVAL_SECONDS;
    public static final EnvOption<Integer> LOOKBACK_SECONDS;
    public static final EnvOption<Integer> THRESHOLD_SECONDS;
    public static final EnvOption<Integer> MAX_LOG_LINES;
    public static final EnvOption<String> EXPECTED_CLIENTS;
    public static final EnvOption<String> GRAFANA_URL;
    public static final EnvOption<String> GRAFANA_API_TOKEN;
    public static final EnvOption<String> GRAFANA_LOKI_DATASOURCE_UID;
    public static final EnvOption<String> LOKI_QUERY;
    public static final EnvOption<String> ALERT_URL;
    public static final EnvOption<Integer> ALERT_COOLDOWN_SECONDS;

    static {
        CHECK_INTERVAL_SECONDS = ENV.optional("LIVENESS_CHECK_INTERVAL_SECONDS", EnvType.integer(), 5);
        LOOKBACK_SECONDS = ENV.optional("LIVENESS_LOOKBACK_SECONDS", EnvType.integer(), 60);
        THRESHOLD_SECONDS = ENV.optional("LIVENESS_THRESHOLD_SECONDS", EnvType.integer(), 15);
        MAX_LOG_LINES = ENV.optional("LIVENESS_MAX_LOG_LINES", EnvType.integer(), 500);
        EXPECTED_CLIENTS = ENV.optional("LIVENESS_EXPECTED_CLIENTS", EnvType.string(), "");
        GRAFANA_URL = ENV.optional("GRAFANA_URL", EnvType.string(), "http://127.0.0.1:3000");
        GRAFANA_API_TOKEN = ENV.optional("GRAFANA_API_TOKEN", EnvType.string(), "");
        GRAFANA_LOKI_DATASOURCE_UID = ENV.optional("GRAFANA_LOKI_DATASOURCE_UID", EnvType.string(), "");
        LOKI_QUERY = ENV.optional("LIVENESS_LOKI_QUERY", EnvType.string(),
                "{stream_type=\"app\"} | json | message=\"Client heartbeat.\"");
        ALERT_URL = ENV.optional("ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        ALERT_COOLDOWN_SECONDS = ENV.optional("LIVENESS_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 300);

        ENV.property("liveness.check-interval-seconds", CHECK_INTERVAL_SECONDS);
        ENV.property("liveness.lookback-seconds", LOOKBACK_SECONDS);
        ENV.property("liveness.threshold-seconds", THRESHOLD_SECONDS);
        ENV.property("liveness.max-log-lines", MAX_LOG_LINES);
        ENV.property("liveness.expected-clients", EXPECTED_CLIENTS);
        ENV.property("liveness.grafana-url", GRAFANA_URL);
        ENV.property("liveness.grafana-api-token", GRAFANA_API_TOKEN);
        ENV.property("liveness.grafana-loki-datasource-uid", GRAFANA_LOKI_DATASOURCE_UID);
        ENV.property("liveness.loki-query", LOKI_QUERY);
        ENV.property("liveness.alert-url", ALERT_URL);
        ENV.property("liveness.alert-cooldown-seconds", ALERT_COOLDOWN_SECONDS);
    }

    private LivenessAnalyzerEnvs() {
    }
}
