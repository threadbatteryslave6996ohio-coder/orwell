package dev.orwell.bucket.person;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything person detection reads from the environment.
 *
 * <p>Built on {@link EnvSchema} directly rather than on {@code AppServerEnv}, because this is no
 * longer a request-serving app: it watches the hub and holds a port only so an operator can ask
 * how it is doing. {@code SERVER_ENGINE} is gone with the two engine main classes it used to pick
 * between.
 */
public final class PersonDetectionEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> SERVER_ADDRESS;
    public static final EnvOption<Integer> SERVER_PORT;
    public static final EnvOption<String> PERSON_DETECTION_HUB_URL;
    public static final EnvOption<String> PERSON_DETECTION_SUBSCRIPTION;
    public static final EnvOption<String> PERSON_DETECTION_SOURCE;
    public static final EnvOption<String> PERSON_DETECTION_ALERT_URL;
    public static final EnvOption<Integer> PERSON_DETECTION_ALERT_COOLDOWN_SECONDS;
    public static final EnvOption<Double> PERSON_DETECTION_MIN_CONFIDENCE;
    public static final EnvOption<String> LOGGER;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;

    private static final EnvSchema SCHEMA;

    static {
        EnvType<Double> doubleType = EnvType.of(Double.class, "double", Double::parseDouble);
        SERVER_ADDRESS = BUILDER.optional("SERVER_ADDRESS", EnvType.string(), "127.0.0.1");
        SERVER_PORT = BUILDER.optional("SERVER_PORT", EnvType.integer(), 9002);
        // Where the hub is. Required: a detector that cannot reach the stream has nothing to do,
        // and should say so at startup rather than sit idle looking healthy.
        PERSON_DETECTION_HUB_URL = BUILDER.required("PERSON_DETECTION_HUB_URL", EnvType.string());
        // A durable cursor name, so a restart resumes where it stopped instead of joining at the
        // head and silently never examining whatever arrived while it was down.
        PERSON_DETECTION_SUBSCRIPTION = BUILDER.optional(
                "PERSON_DETECTION_SUBSCRIPTION", EnvType.string(), "person-detection");
        // Blank watches every camera.
        PERSON_DETECTION_SOURCE = BUILDER.optional("PERSON_DETECTION_SOURCE", EnvType.string(), "");
        PERSON_DETECTION_ALERT_URL = BUILDER.optional(
                "PERSON_DETECTION_ALERT_URL", EnvType.string(), "http://127.0.0.1:9000/alerts");
        PERSON_DETECTION_ALERT_COOLDOWN_SECONDS =
                BUILDER.optional("PERSON_DETECTION_ALERT_COOLDOWN_SECONDS", EnvType.integer(), 60);
        PERSON_DETECTION_MIN_CONFIDENCE =
                BUILDER.optional("PERSON_DETECTION_MIN_CONFIDENCE", doubleType, 0.35);
        // Which sinks the logger gets; blank keeps the repo default (Loki when LOKI_URL is set,
        // console otherwise). LoggerSetup parses it — see LoggerMode for the values.
        LOGGER = BUILDER.optional("LOGGER", EnvType.string(), "");
        LOKI_URL = BUILDER.optional("LOKI_URL", EnvType.string(), "");
        LOKI_TENANT_ID = BUILDER.optional("LOKI_TENANT_ID", EnvType.string(), "");
        SCHEMA = BUILDER.build();
    }

    public static Env load() throws IOException {
        return SCHEMA.from(EnvFiles.load());
    }

    private PersonDetectionEnvs() {
    }
}
