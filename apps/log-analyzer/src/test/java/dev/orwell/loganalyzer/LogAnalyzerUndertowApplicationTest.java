package dev.orwell.loganalyzer;

import dev.orwell.env.Env;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The Undertow engine builds its own logger, and the three keys it reads are declared on
 * {@code AppServerEnv} as optional <em>without</em> a default — so {@code Env.get} throws for any
 * of them that is unset. Reading them directly would therefore kill the server at startup in the
 * ordinary configuration, which is the one where none of them is set.
 */
class LogAnalyzerUndertowApplicationTest {
    /** SERVER_ADDRESS and SERVER_PORT are the only required keys; the rest is what varies here. */
    private static Env envWith(Map<String, String> values) {
        Map<String, String> withRequired = new LinkedHashMap<>(values);
        withRequired.put("SERVER_ADDRESS", "0.0.0.0");
        withRequired.put("SERVER_PORT", "8080");
        return LogAnalyzerEnvs.ENV.schema().from(withRequired);
    }

    @Test
    void anUnsetLoggerKeyReadsAsBlankRatherThanThrowing() {
        Env env = envWith(Map.of());

        assertEquals("", LogAnalyzerUndertowApplication.optional(env, LogAnalyzerEnvs.ENV.LOGGER));
        assertEquals("", LogAnalyzerUndertowApplication.optional(env, LogAnalyzerEnvs.ENV.LOKI_URL));
        assertEquals("", LogAnalyzerUndertowApplication.optional(env, LogAnalyzerEnvs.ENV.LOKI_TENANT_ID));
    }

    @Test
    void aConfiguredValueIsPassedThrough() {
        Env env = envWith(Map.of(
                "LOGGER", "loki-with-fallback",
                "LOKI_URL", "http://loki:3100/loki/api/v1/push"));

        assertEquals("loki-with-fallback", LogAnalyzerUndertowApplication.optional(env, LogAnalyzerEnvs.ENV.LOGGER));
        assertEquals("http://loki:3100/loki/api/v1/push",
                LogAnalyzerUndertowApplication.optional(env, LogAnalyzerEnvs.ENV.LOKI_URL));
    }
}
