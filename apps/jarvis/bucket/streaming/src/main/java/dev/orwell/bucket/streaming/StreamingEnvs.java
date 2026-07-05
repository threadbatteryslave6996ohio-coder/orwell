package dev.orwell.bucket.streaming;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.Map;

public final class StreamingEnvs {
    public static final EnvOption<String> STREAM_ANALYSIS_ENDPOINT;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        STREAM_ANALYSIS_ENDPOINT = builder.optional("STREAM_ANALYSIS_ENDPOINT", EnvType.string(), "");
        ENV = builder.build();
    }

    private StreamingEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }
}
