package dev.orwell.health;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything the health monitor reads from the environment.
 *
 * <p>Built on {@link EnvSchema} directly rather than on {@code AppServerEnv}: this serves two routes
 * on the Undertow runtime and has no Spring context to feed properties into.
 */
public final class HealthMonitorEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> SERVER_ADDRESS;
    public static final EnvOption<Integer> SERVER_PORT;
    public static final EnvOption<String> HEALTH_MONITOR_TARGETS;
    public static final EnvOption<Integer> HEALTH_MONITOR_INTERVAL_SECONDS;
    public static final EnvOption<Integer> HEALTH_MONITOR_TIMEOUT_SECONDS;
    public static final EnvOption<Integer> HEALTH_MONITOR_FAILURES_BEFORE_DOWN;
    public static final EnvOption<Integer> HEALTH_MONITOR_SUCCESSES_BEFORE_UP;
    public static final EnvOption<String> HEALTH_MONITOR_ALERT_URL;
    public static final EnvOption<String> LOGGER;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;

    private static final EnvSchema SCHEMA;

    static {
        SERVER_ADDRESS = BUILDER.optional("SERVER_ADDRESS", EnvType.string(), "127.0.0.1");
        SERVER_PORT = BUILDER.optional("SERVER_PORT", EnvType.integer(), 9005);

        // What to check, as `name=url` pairs separated by commas. Blank checks nothing, which is
        // said loudly at startup — the compose service fills it in with the stack's own services.
        HEALTH_MONITOR_TARGETS = BUILDER.optional("HEALTH_MONITOR_TARGETS", EnvType.string(), "");
        // Seconds between rounds. 30 is a compromise worth naming: it bounds how long an outage can
        // go unnoticed at roughly interval x failuresBeforeDown, so the default detects a real
        // outage inside about a minute while adding two requests a minute per service.
        HEALTH_MONITOR_INTERVAL_SECONDS =
                BUILDER.optional("HEALTH_MONITOR_INTERVAL_SECONDS", EnvType.integer(), 30);
        // Per-request timeout. MUST stay below the interval: a timeout longer than the gap between
        // rounds means a dead target is still being waited on when the next round starts.
        HEALTH_MONITOR_TIMEOUT_SECONDS =
                BUILDER.optional("HEALTH_MONITOR_TIMEOUT_SECONDS", EnvType.integer(), 5);

        // Consecutive results before the state flips. Two to go down, one to come back: a single
        // refused connection is a restart or a GC pause, not an outage, and a monitor that pages on
        // each of those gets muted — while believing good news quickly costs at most one extra
        // alert if the service is flapping.
        HEALTH_MONITOR_FAILURES_BEFORE_DOWN =
                BUILDER.optional("HEALTH_MONITOR_FAILURES_BEFORE_DOWN", EnvType.integer(), 2);
        HEALTH_MONITOR_SUCCESSES_BEFORE_UP =
                BUILDER.optional("HEALTH_MONITOR_SUCCESSES_BEFORE_UP", EnvType.integer(), 1);

        // Where a transition is POSTed, e.g. http://alerting/alerts. Blank logs only — and the log
        // line is written before the alert is attempted, so an alerting outage cannot lose it.
        HEALTH_MONITOR_ALERT_URL =
                BUILDER.optional("HEALTH_MONITOR_ALERT_URL", EnvType.string(), "");

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

    private HealthMonitorEnvs() {
    }
}
