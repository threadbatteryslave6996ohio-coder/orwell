package dev.orwell.alerting;

import dev.orwell.bootstrap.AppServerEnv;
import dev.orwell.env.EnvOption;
import dev.orwell.env.EnvType;

public final class AlertEnvs {
    public static final AppServerEnv ENV = new AppServerEnv(false, false);

    public static final EnvOption<Boolean> ALERT_EMAIL_ENABLED;
    public static final EnvOption<String> ALERT_EMAIL_TO;
    public static final EnvOption<String> ALERT_EMAIL_FROM;
    public static final EnvOption<String> SMTP_HOST;
    public static final EnvOption<Integer> SMTP_PORT;
    public static final EnvOption<String> SMTP_USERNAME;
    public static final EnvOption<String> SMTP_PASSWORD;
    public static final EnvOption<Boolean> SMTP_USE_TLS;
    public static final EnvOption<String> ALERT_LOG_FILE;

    static {
        ALERT_EMAIL_ENABLED = ENV.optional("ALERT_EMAIL_ENABLED", EnvType.bool(), false);
        ALERT_EMAIL_TO = ENV.optional("ALERT_EMAIL_TO", EnvType.string(), "");
        ALERT_EMAIL_FROM = ENV.optional("ALERT_EMAIL_FROM", EnvType.string(), "");
        SMTP_HOST = ENV.optional("SMTP_HOST", EnvType.string(), "");
        SMTP_PORT = ENV.optional("SMTP_PORT", EnvType.integer(), 587);
        SMTP_USERNAME = ENV.optional("SMTP_USERNAME", EnvType.string(), "");
        SMTP_PASSWORD = ENV.optional("SMTP_PASSWORD", EnvType.string(), "");
        SMTP_USE_TLS = ENV.optional("SMTP_USE_TLS", EnvType.bool(), true);
        ALERT_LOG_FILE = ENV.optional("ALERT_LOG_FILE", EnvType.string(), "/var/log/streaming/alerts.log");

        ENV.property("alert.email.enabled", ALERT_EMAIL_ENABLED);
        ENV.property("alert.email.to", ALERT_EMAIL_TO);
        ENV.property("alert.email.from", ALERT_EMAIL_FROM);
        ENV.property("alert.smtp.host", SMTP_HOST);
        ENV.property("alert.smtp.port", SMTP_PORT);
        ENV.property("alert.smtp.username", SMTP_USERNAME);
        ENV.property("alert.smtp.password", SMTP_PASSWORD);
        ENV.property("alert.smtp.use-tls", SMTP_USE_TLS);
        ENV.property("alert.log-file", ALERT_LOG_FILE);
    }

    private AlertEnvs() {
    }
}
