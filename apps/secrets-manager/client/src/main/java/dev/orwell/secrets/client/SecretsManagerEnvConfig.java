package dev.orwell.secrets.client;

import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

public final class SecretsManagerEnvConfig {
    public static final EnvOption<String> URL;
    public static final EnvOption<String> TOKEN;
    public static final EnvOption<String> CLIENT_ID;
    public static final EnvOption<Long> BUNDLE_ID;
    public static final EnvSchema SCHEMA;

    static {
        var builder = EnvSchema.builder();
        URL = builder.required("SECRETS_MANAGER_URL", EnvType.string());
        TOKEN = builder.required("SECRETS_MANAGER_TOKEN", EnvType.string());
        CLIENT_ID = builder.required("SECRETS_MANAGER_CLIENT_ID", EnvType.string());
        BUNDLE_ID = builder.required("SECRETS_BUNDLE_ID", EnvType.longInteger());
        SCHEMA = builder.build();
    }

    private SecretsManagerEnvConfig() {
    }
}
