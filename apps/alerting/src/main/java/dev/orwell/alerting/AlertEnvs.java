package dev.orwell.alerting;

import dev.orwell.env.Env;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvSchema;
import dev.orwell.env.EnvType;

import java.util.Map;

public final class AlertEnvs {
    public static final EnvOption<String> ALERT_SERVER_HOST;
    public static final EnvOption<Integer> ALERT_SERVER_PORT;
    public static final EnvOption<Boolean> ALERT_EMAIL_ENABLED;
    public static final EnvOption<String> ALERT_EMAIL_TO;
    public static final EnvOption<String> ALERT_EMAIL_FROM;
    public static final EnvOption<String> SMTP_HOST;
    public static final EnvOption<Integer> SMTP_PORT;
    public static final EnvOption<String> SMTP_USERNAME;
    public static final EnvOption<String> SMTP_PASSWORD;
    public static final EnvOption<Boolean> SMTP_USE_TLS;
    public static final EnvOption<String> ALERT_LOG_FILE;
    public static final EnvSchema ENV;

    static {
        var builder = EnvSchema.builder();
        ALERT_SERVER_HOST = builder.optional("ALERT_SERVER_HOST", EnvType.string(), "127.0.0.1");
        ALERT_SERVER_PORT = builder.optional("ALERT_SERVER_PORT", EnvType.integer(), 9000);
        ALERT_EMAIL_ENABLED = builder.optional("ALERT_EMAIL_ENABLED", EnvType.bool(), false);
        ALERT_EMAIL_TO = builder.optional("ALERT_EMAIL_TO", EnvType.string(), "");
        ALERT_EMAIL_FROM = builder.optional("ALERT_EMAIL_FROM", EnvType.string(), "");
        SMTP_HOST = builder.optional("SMTP_HOST", EnvType.string(), "");
        SMTP_PORT = builder.optional("SMTP_PORT", EnvType.integer(), 587);
        SMTP_USERNAME = builder.optional("SMTP_USERNAME", EnvType.string(), "");
        SMTP_PASSWORD = builder.optional("SMTP_PASSWORD", EnvType.string(), "");
        SMTP_USE_TLS = builder.optional("SMTP_USE_TLS", EnvType.bool(), true);
        ALERT_LOG_FILE = builder.optional("ALERT_LOG_FILE", EnvType.string(), "/var/log/streaming/alerts.log");
        ENV = builder.build();
    }

    private AlertEnvs() {
    }

    public static Env from(Map<String, String> source) {
        return ENV.from(source);
    }
}
