package dev.orwell.bucket.fixture;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything fixture detection reads from the environment.
 *
 * <p>Built on {@link EnvSchema} directly rather than on {@code AppServerEnv}, as
 * {@code jarvis-person-detection} is: this watches the hub and holds a port only so an operator can
 * ask how it is doing and see what it is looking at.
 *
 * <p>The prefix is {@code FIXTURE_DETECTION_} because a prefix is the only thing that says which
 * process a setting reaches — the reason every {@code DETECTION_*} key was retired when one service
 * became several.
 */
public final class FixtureDetectionEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> SERVER_ADDRESS;
    public static final EnvOption<Integer> SERVER_PORT;
    public static final EnvOption<String> FIXTURE_DETECTION_HUB_URL;
    public static final EnvOption<String> FIXTURE_DETECTION_SUBSCRIPTION;
    public static final EnvOption<String> FIXTURE_DETECTION_SOURCE;
    public static final EnvOption<String> FIXTURE_DETECTION_DEFINITIONS;
    public static final EnvOption<String> FIXTURE_DETECTION_DEFINITIONS_FILE;
    public static final EnvOption<String> FIXTURE_DETECTION_ALERT_URL;
    public static final EnvOption<String> LOGGER;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;

    private static final EnvSchema SCHEMA;

    static {
        SERVER_ADDRESS = BUILDER.optional("SERVER_ADDRESS", EnvType.string(), "127.0.0.1");
        SERVER_PORT = BUILDER.optional("SERVER_PORT", EnvType.integer(), 9004);
        // Where the hub is. Required: a detector that cannot reach the stream has nothing to look at,
        // and should say so at startup rather than sit idle looking healthy.
        FIXTURE_DETECTION_HUB_URL = BUILDER.required("FIXTURE_DETECTION_HUB_URL", EnvType.string());
        // A durable cursor name, so a restart resumes where it stopped. It matters more here than for
        // person detection: a missed stretch of frames is a missed transition, and a fixture's state
        // is then wrong until the next time somebody touches the switch.
        FIXTURE_DETECTION_SUBSCRIPTION = BUILDER.optional(
                "FIXTURE_DETECTION_SUBSCRIPTION", EnvType.string(), "fixture-detection");
        // Narrows the *stream* to one camera. Usually leave it blank and let each fixture name its
        // own source — this is for the case where one camera's frames are all that is wanted over
        // the wire at all.
        FIXTURE_DETECTION_SOURCE = BUILDER.optional(
                "FIXTURE_DETECTION_SOURCE", EnvType.string(), "");

        // What to watch, as JSON. A file if one is named, inline otherwise; see FixtureDefinitions
        // for the shape and why it is not a set of environment keys.
        FIXTURE_DETECTION_DEFINITIONS = BUILDER.optional(
                "FIXTURE_DETECTION_DEFINITIONS", EnvType.string(), "");
        FIXTURE_DETECTION_DEFINITIONS_FILE = BUILDER.optional(
                "FIXTURE_DETECTION_DEFINITIONS_FILE", EnvType.string(), "");

        // Where a transition is POSTed. Blank by default, which is the deliberate difference from
        // person detection: transitions are rare and the log line is the record, so alerting is
        // something to switch on rather than something to have failing quietly in the background.
        FIXTURE_DETECTION_ALERT_URL = BUILDER.optional(
                "FIXTURE_DETECTION_ALERT_URL", EnvType.string(), "");

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

    private FixtureDetectionEnvs() {
    }
}
