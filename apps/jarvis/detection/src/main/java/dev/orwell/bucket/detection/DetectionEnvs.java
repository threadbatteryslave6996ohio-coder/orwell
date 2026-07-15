package dev.orwell.bucket.detection;

import dev.orwell.bootstrap.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class DetectionEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);
    public static final EnvOption<String> DETECTION_ALERT_URL;
    public static final EnvOption<Integer> DETECTION_ALERT_COOLDOWN_SECONDS;
    public static final EnvOption<Double> DETECTION_MIN_CONFIDENCE;

    static {
        DETECTION_ALERT_URL = ENV.optional("DETECTION_ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        DETECTION_ALERT_COOLDOWN_SECONDS = ENV.optional("DETECTION_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 60);
        DETECTION_MIN_CONFIDENCE = ENV.optional("DETECTION_MIN_CONFIDENCE", EnvType.of(Double.class, "double", Double::parseDouble), 0.0);
        ENV.property("detection.alert-url", DETECTION_ALERT_URL);
        ENV.property("detection.cooldown-seconds", DETECTION_ALERT_COOLDOWN_SECONDS);
        ENV.property("detection.min-confidence", DETECTION_MIN_CONFIDENCE);
    }

    private DetectionEnvs() {
    }
}
