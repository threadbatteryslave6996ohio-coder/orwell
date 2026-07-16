package dev.orwell.alerting;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.bootstrap.web.SharedJson;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Records incoming alerts and, when email is configured, forwards them over SMTP. Extracted from
 * the former hand-rolled HTTP server so the transport (Spring MVC) stays thin.
 */
@Service
public class AlertService {
    private final boolean emailEnabled;
    private final String emailTo;
    private final String emailFrom;
    private final SmtpConfig smtp;
    private final JsonLogger logger;
    private final ObjectMapper objectMapper = SharedJson.mapper();

    public AlertService(
            @Value("${alert.email.enabled}") boolean emailEnabled,
            @Value("${alert.email.to}") String emailTo,
            @Value("${alert.email.from}") String emailFrom,
            @Value("${alert.smtp.host}") String smtpHost,
            @Value("${alert.smtp.port}") int smtpPort,
            @Value("${alert.smtp.username}") String smtpUsername,
            @Value("${alert.smtp.password}") String smtpPassword,
            @Value("${alert.smtp.use-tls}") boolean smtpUseTls,
            @Value("${alert.log-file}") String logFile
    ) throws IOException {
        this.emailEnabled = emailEnabled;
        this.emailTo = emailTo;
        if (emailFrom.isBlank()) {
            emailFrom = emailTo.isBlank() ? "alerts@localhost" : emailTo;
        }
        this.emailFrom = emailFrom;
        this.smtp = new SmtpConfig(smtpHost, smtpPort, smtpUsername, smtpPassword, smtpUseTls);
        this.logger = new JsonLogger(Path.of(logFile));
    }

    public boolean emailEnabled() {
        return emailEnabled;
    }

    /** Handles an inbound alert, returning the response payload. */
    public Map<String, Object> handleAlert(Map<String, Object> alert) {
        logger.info("alert.received", Map.of("alert", alert));
        boolean emailSent = false;
        String emailError = null;
        try {
            emailSent = sendEmail(alert);
        } catch (Exception exception) {
            emailError = exception.getMessage();
            logger.error("alert.email.error", Map.of("error", emailError), exception);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("emailEnabled", emailEnabled);
        response.put("emailSent", emailSent);
        response.put("emailError", emailError);
        return response;
    }

    private boolean sendEmail(Map<String, Object> alert) throws MessagingException, IOException {
        if (!emailEnabled || emailTo.isBlank() || !smtp.isConfigured()) {
            logger.info("alert.email.skipped", Map.of("reason", "missing email settings"));
            return false;
        }

        Session session = smtp.createSession();

        String event = String.valueOf(alert.getOrDefault("event", "alert"));
        String source = String.valueOf(alert.getOrDefault("source", "unknown"));
        String body = objectMapper.writeValueAsString(alert);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(emailFrom));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailTo));
        message.setSubject("[Alert] " + event + " from " + source);
        message.setText(body, StandardCharsets.UTF_8.name());
        jakarta.mail.Transport.send(message);
        return true;
    }
}
