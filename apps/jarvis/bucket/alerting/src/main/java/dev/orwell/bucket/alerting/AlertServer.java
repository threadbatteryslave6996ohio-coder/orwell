package dev.orwell.bucket.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

final class AlertServer {
    private final String host;
    private final int port;
    private final boolean emailEnabled;
    private final String emailTo;
    private final String emailFrom;
    private final String smtpHost;
    private final int smtpPort;
    private final String smtpUsername;
    private final String smtpPassword;
    private final boolean smtpUseTls;
    private final JsonLogger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AlertServer(String host, int port, boolean emailEnabled, String emailTo, String emailFrom,
                        String smtpHost, int smtpPort, String smtpUsername, String smtpPassword,
                        boolean smtpUseTls, JsonLogger logger) {
        this.host = host;
        this.port = port;
        this.emailEnabled = emailEnabled;
        this.emailTo = emailTo;
        this.emailFrom = emailFrom;
        this.smtpHost = smtpHost;
        this.smtpPort = smtpPort;
        this.smtpUsername = smtpUsername;
        this.smtpPassword = smtpPassword;
        this.smtpUseTls = smtpUseTls;
        this.logger = logger;
    }

    static AlertServer fromEnvironment() throws IOException {
        String host = getenv("ALERT_SERVER_HOST", "127.0.0.1");
        int port = Integer.parseInt(getenv("ALERT_SERVER_PORT", "9000"));
        boolean emailEnabled = Boolean.parseBoolean(getenv("ALERT_EMAIL_ENABLED", "false"));
        String emailTo = getenv("ALERT_EMAIL_TO", "").trim();
        String emailFrom = getenv("ALERT_EMAIL_FROM", emailTo.isBlank() ? "alerts@localhost" : emailTo).trim();
        String smtpHost = getenv("SMTP_HOST", "").trim();
        int smtpPort = Integer.parseInt(getenv("SMTP_PORT", "587"));
        String smtpUsername = getenv("SMTP_USERNAME", "").trim();
        String smtpPassword = getenv("SMTP_PASSWORD", "").trim();
        boolean smtpUseTls = Boolean.parseBoolean(getenv("SMTP_USE_TLS", "true"));
        JsonLogger logger = new JsonLogger(Path.of(getenv("ALERT_LOG_FILE", "/var/log/streaming/alerts.log")));
        return new AlertServer(host, port, emailEnabled, emailTo, emailFrom, smtpHost, smtpPort, smtpUsername, smtpPassword, smtpUseTls, logger);
    }

    void run() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", this::writeHealth);
        server.createContext("/alerts", this::handleAlert);
        logger.info("alert.server.start", Map.of("host", host, "port", port, "emailEnabled", emailEnabled));
        server.start();
    }

    private void writeHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("success", false, "error", "method not allowed"));
            return;
        }
        writeJson(exchange, 200, Map.of("success", true, "status", "healthy", "emailEnabled", emailEnabled));
    }

    private void handleAlert(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("success", false, "error", "method not allowed"));
            return;
        }
        Map<String, Object> alert;
        try (var body = exchange.getRequestBody()) {
            alert = objectMapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception exception) {
            writeJson(exchange, 400, Map.of("success", false, "error", "invalid json"));
            return;
        }

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
        writeJson(exchange, 200, response);
    }

    private boolean sendEmail(Map<String, Object> alert) throws MessagingException, IOException {
        if (!emailEnabled || emailTo.isBlank() || smtpHost.isBlank()) {
            logger.info("alert.email.skipped", Map.of("reason", "missing email settings"));
            return false;
        }

        Properties props = new Properties();
        props.put("mail.smtp.host", smtpHost);
        props.put("mail.smtp.port", String.valueOf(smtpPort));
        props.put("mail.smtp.auth", String.valueOf(!smtpUsername.isBlank()));
        props.put("mail.smtp.starttls.enable", String.valueOf(smtpUseTls));

        Session session = Session.getInstance(props, smtpUsername.isBlank() ? null : new jakarta.mail.Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(smtpUsername, smtpPassword);
            }
        });

        String event = String.valueOf(alert.getOrDefault("event", "alert"));
        String source = String.valueOf(alert.getOrDefault("source", "unknown"));
        String body = objectMapper.writeValueAsString(alert);
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress(emailFrom));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailTo));
        message.setSubject("[Alert] " + event + " from " + source);
        message.setText(body, StandardCharsets.UTF_8.name());
        Transport.send(message);
        return true;
    }

    private void writeJson(HttpExchange exchange, int status, Map<String, ?> payload) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String getenv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
