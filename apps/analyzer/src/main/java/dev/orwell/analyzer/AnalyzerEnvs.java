package dev.orwell.analyzer;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class AnalyzerEnvs {
    public static final EnvOption<String> ANALYZER_HOST;
    public static final EnvOption<Integer> ANALYZER_PORT;
    public static final EnvOption<String> AUTH_SERVER_URL;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        ANALYZER_HOST = builder.optional("ANALYZER_HOST", EnvType.string(), "127.0.0.1");
        ANALYZER_PORT = builder.optional("ANALYZER_PORT", EnvType.integer(), 9200);
        AUTH_SERVER_URL = builder.optional("AUTH_SERVER_URL", EnvType.string(), "http://127.0.0.1:8081");
        ENV = builder.build();
    }

    private AnalyzerEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("server.address", env.get(ANALYZER_HOST));
        values.put("server.port", env.get(ANALYZER_PORT));
        values.put("clippy.auth.base-url", env.get(AUTH_SERVER_URL));
        return values;
    }
}
