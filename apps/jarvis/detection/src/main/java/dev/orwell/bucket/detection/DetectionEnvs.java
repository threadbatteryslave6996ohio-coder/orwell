package dev.orwell.bucket.detection;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.HashMap;
import java.util.Map;

public final class DetectionEnvs {
    public static final EnvOption<String> DETECTION_SERVER_HOST;
    public static final EnvOption<Integer> DETECTION_SERVER_PORT;
    public static final EnvOption<String> DETECTION_ALERT_URL;
    public static final EnvOption<Integer> DETECTION_ALERT_COOLDOWN_SECONDS;
    public static final EnvOption<Double> DETECTION_MIN_CONFIDENCE;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        DETECTION_SERVER_HOST = builder.optional("DETECTION_SERVER_HOST", EnvType.string(), "127.0.0.1");
        DETECTION_SERVER_PORT = builder.optional("DETECTION_SERVER_PORT", EnvType.integer(), 9001);
        DETECTION_ALERT_URL = builder.optional("DETECTION_ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        DETECTION_ALERT_COOLDOWN_SECONDS = builder.optional("DETECTION_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 60);
        DETECTION_MIN_CONFIDENCE = builder.optional("DETECTION_MIN_CONFIDENCE", EnvType.of(Double.class, "double", Double::parseDouble), 0.0);
        ENV = builder.build();
    }

    private DetectionEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }

    public static Map<String, Object> springProperties(Env env) {
        Map<String, Object> values = new HashMap<>();
        values.put("server.address", env.get(DETECTION_SERVER_HOST));
        values.put("server.port", env.get(DETECTION_SERVER_PORT));
        values.put("detection.alert-url", env.get(DETECTION_ALERT_URL));
        values.put("detection.cooldown-seconds", env.get(DETECTION_ALERT_COOLDOWN_SECONDS));
        values.put("detection.min-confidence", env.get(DETECTION_MIN_CONFIDENCE));
        return values;
    }
}
