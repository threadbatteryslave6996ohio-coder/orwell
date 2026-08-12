package dev.orwell.bucket.retention.worker;

import dev.orwell.env.Env;
import dev.orwell.env.EnvClassBuilder;
import dev.orwell.env.EnvFiles;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.io.IOException;

/**
 * Everything the retention worker reads from the environment.
 *
 * <p>Built on {@link EnvSchema} directly rather than on {@code AppServerEnv}: this process serves
 * no requests, so {@code SERVER_ADDRESS} and {@code SERVER_PORT} would be settings an operator
 * could set and watch do nothing.
 */
public final class RetentionWorkerEnvs {
    private static final EnvClassBuilder BUILDER = EnvSchema.builder();

    public static final EnvOption<String> RETENTION_DATASOURCE_URL;
    public static final EnvOption<String> RETENTION_DATASOURCE_USERNAME;
    public static final EnvOption<String> RETENTION_DATASOURCE_PASSWORD;
    public static final EnvOption<Long> RETENTION_FRAME_MAX_BYTES;
    public static final EnvOption<Integer> RETENTION_FRAME_MAX_AGE_SECONDS;
    public static final EnvOption<Integer> RETENTION_SWEEP_SECONDS;
    public static final EnvOption<String> LOKI_URL;
    public static final EnvOption<String> LOKI_TENANT_ID;

    private static final EnvSchema SCHEMA;

    static {
        RETENTION_DATASOURCE_URL = BUILDER.required("RETENTION_DATASOURCE_URL", EnvType.string());
        RETENTION_DATASOURCE_USERNAME =
                BUILDER.required("RETENTION_DATASOURCE_USERNAME", EnvType.string());
        RETENTION_DATASOURCE_PASSWORD =
                BUILDER.required("RETENTION_DATASOURCE_PASSWORD", EnvType.string());
        // frame_events is bounded by whichever of these two bites first. The byte budget is what
        // protects the disk on a busy stream — it holds the footprint constant and lets the replay
        // window vary — and 2 GiB is chosen to be a modest tenant on the one Postgres the whole
        // repo shares. The age bound is what keeps a quiet camera's frames from living forever;
        // set either to 0 to disable that bound.
        RETENTION_FRAME_MAX_BYTES = BUILDER.optional(
                "RETENTION_FRAME_MAX_BYTES", EnvType.longInteger(), 2L * 1024 * 1024 * 1024);
        RETENTION_FRAME_MAX_AGE_SECONDS =
                BUILDER.optional("RETENTION_FRAME_MAX_AGE_SECONDS", EnvType.integer(), 300);
        RETENTION_SWEEP_SECONDS = BUILDER.optional("RETENTION_SWEEP_SECONDS", EnvType.integer(), 30);
        LOKI_URL = BUILDER.optional("LOKI_URL", EnvType.string(), "");
        LOKI_TENANT_ID = BUILDER.optional("LOKI_TENANT_ID", EnvType.string(), "");
        SCHEMA = BUILDER.build();
    }

    /**
     * Values come from a {@code .env} file found upwards from the working directory, with real
     * environment variables taking precedence — the same rule the rest of the repo follows.
     */
    public static Env load() throws IOException {
        return SCHEMA.from(EnvFiles.load());
    }

    private RetentionWorkerEnvs() {
    }
}
