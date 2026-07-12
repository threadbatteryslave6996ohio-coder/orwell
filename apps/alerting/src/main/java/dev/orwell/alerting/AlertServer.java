package dev.orwell.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.env.http.HttpExchangeResponses;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import dev.orwell.env.Env;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class AlertServer {
    private final String host;
    private final int port;
    private final boolean emailEnabled;
    private final String emailTo;
    private final String emailFrom;
    private final SmtpConfig smtp;
    private final JsonLogger logger;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AlertServer(String host, int port, boolean emailEnabled, String emailTo, String emailFrom,
                        SmtpConfig smtp, JsonLogger logger) {
        this.host = host;
        this.port = port;
        this.emailEnabled = emailEnabled;
        this.emailTo = emailTo;
        this.emailFrom = emailFrom;
        this.smtp = smtp;
        this.logger = logger;
    }

    static AlertServer fromEnv(Env env) throws IOException {
        String host = env.get(AlertEnvs.ALERT_SERVER_HOST);
        int port = env.get(AlertEnvs.ALERT_SERVER_PORT);
        boolean emailEnabled = env.get(AlertEnvs.ALERT_EMAIL_ENABLED);
        String emailTo = env.get(AlertEnvs.ALERT_EMAIL_TO);
        String emailFrom = env.get(AlertEnvs.ALERT_EMAIL_FROM);
        if (emailFrom.isBlank()) {
            emailFrom = emailTo.isBlank() ? "alerts@localhost" : emailTo;
        }
        SmtpConfig smtp = SmtpConfig.fromEnv(env);
        JsonLogger logger = new JsonLogger(Path.of(env.get(AlertEnvs.ALERT_LOG_FILE)));
        return new AlertServer(host, port, emailEnabled, emailTo, emailFrom, smtp, logger);
    }

    void run() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", this::writeHealth);
        server.createContext("/alerts", this::handleAlert);
        logger.info("alert.server.start", Map.of("host", host, "port", port, "emailEnabled", emailEnabled));
        server.start();
    }

    private void writeHealth(HttpExchange exchange) throws IOException {
        if (!HttpExchangeResponses.requireMethod(exchange, "GET")) {
            return;
        }
        HttpExchangeResponses.writeJson(
                exchange,
                200,
                Map.of("success", true, "status", "healthy", "emailEnabled", emailEnabled),
                objectMapper
        );
    }

    private void handleAlert(HttpExchange exchange) throws IOException {
        if (!HttpExchangeResponses.requireMethod(exchange, "POST")) {
            return;
        }
        Map<String, Object> alert;
        try (var body = exchange.getRequestBody()) {
            alert = objectMapper.readValue(body, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        } catch (Exception exception) {
            HttpExchangeResponses.writeJson(
                    exchange,
                    400,
                    Map.of("success", false, "error", "invalid json"),
                    objectMapper
            );
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
        HttpExchangeResponses.writeJson(exchange, 200, response, objectMapper);
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
        Transport.send(message);
        return true;
    }

}
