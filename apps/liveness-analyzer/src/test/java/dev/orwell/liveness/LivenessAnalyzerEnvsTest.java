package dev.orwell.liveness;

import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LivenessAnalyzerEnvsTest {
    @Test
    void resolvesDefaultsAsSpringProperties() {
        Env env = LivenessAnalyzerEnvs.ENV.schema().from(Map.of(
                "SERVER_ADDRESS", "0.0.0.0",
                "SERVER_PORT", "8085"
        ));

        assertEquals(5, env.get(LivenessAnalyzerEnvs.CHECK_INTERVAL_SECONDS));
        assertEquals(15, env.get(LivenessAnalyzerEnvs.THRESHOLD_SECONDS));
        assertEquals(60, env.get(LivenessAnalyzerEnvs.LOOKBACK_SECONDS));
        assertEquals("", env.get(LivenessAnalyzerEnvs.EXPECTED_CLIENTS));
        assertEquals("{stream_type=\"app\"} | json | message=\"Client heartbeat.\"",
                env.get(LivenessAnalyzerEnvs.LOKI_QUERY));

        Map<String, Object> properties = LivenessAnalyzerEnvs.ENV.springProperties(env);
        assertEquals(5, properties.get("liveness.check-interval-seconds"));
        assertEquals("http://127.0.0.1:9000/alerts", properties.get("liveness.alert-url"));
    }
}
