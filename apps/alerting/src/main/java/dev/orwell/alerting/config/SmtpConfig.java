package dev.orwell.alerting.config;

import dev.orwell.env.Env;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;

import java.util.Properties;

/**
 * Groups the SMTP connection settings for the alert server so they can be read,
 * passed around, and turned into a mail {@link Session} in one place.
 */
public record SmtpConfig(String host, int port, String username, String password, boolean useTls) {

    static SmtpConfig fromEnv(Env env) {
        return new SmtpConfig(
                env.get(AlertEnvs.SMTP_HOST),
                env.get(AlertEnvs.SMTP_PORT),
                env.get(AlertEnvs.SMTP_USERNAME),
                env.get(AlertEnvs.SMTP_PASSWORD),
                env.get(AlertEnvs.SMTP_USE_TLS)
        );
    }

    public boolean isConfigured() {
        return !host.isBlank();
    }

    boolean authEnabled() {
        return !username.isBlank();
    }

    public Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", String.valueOf(authEnabled()));
        props.put("mail.smtp.starttls.enable", String.valueOf(useTls));

        if (!authEnabled()) {
            return Session.getInstance(props, null);
        }
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });
    }
}
