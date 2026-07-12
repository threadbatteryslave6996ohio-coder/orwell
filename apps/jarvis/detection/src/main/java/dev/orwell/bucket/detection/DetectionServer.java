package dev.orwell.bucket.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.orwell.env.Env;
import dev.orwell.env.http.HttpExchangeResponses;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DetectionServer {
    private final String host;
    private final int port;
    private final String alertUrl;
    private final PersonDetector detector;
    private final CooldownTracker cooldowns;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private int detectionsTotal;
    private int alertsSentTotal;

    private DetectionServer(String host, int port, String alertUrl, PersonDetector detector, int cooldownSeconds) {
        this.host = host;
        this.port = port;
        this.alertUrl = alertUrl;
        this.detector = detector;
        this.cooldowns = new CooldownTracker(cooldownSeconds);
    }

    static DetectionServer fromEnv(Env env) {
        String host = env.get(DetectionEnvs.DETECTION_SERVER_HOST);
        int port = env.get(DetectionEnvs.DETECTION_SERVER_PORT);
        String alertUrl = env.get(DetectionEnvs.DETECTION_ALERT_URL);
        int cooldownSeconds = env.get(DetectionEnvs.DETECTION_ALERT_COOLDOWN_SECONDS);
        double minConfidence = env.get(DetectionEnvs.DETECTION_MIN_CONFIDENCE);
        PersonDetector detector = new HogPersonDetector(minConfidence);
        return new DetectionServer(host, port, alertUrl, detector, cooldownSeconds);
    }

    void run() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/health", this::writeHealth);
        server.createContext("/detect", this::detect);
        server.start();
    }

    private void writeHealth(HttpExchange exchange) throws IOException {
        if (!HttpExchangeResponses.requireMethod(exchange, "GET")) {
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("status", "healthy");
        response.put("detectionsTotal", detectionsTotal);
        response.put("alertsSentTotal", alertsSentTotal);
        HttpExchangeResponses.writeJson(exchange, 200, response, mapper);
    }

    private void detect(HttpExchange exchange) throws IOException {
        if (!HttpExchangeResponses.requireMethod(exchange, "POST")) {
            return;
        }
        Map<String, Object> payload;
        try (InputStream input = exchange.getRequestBody()) {
            payload = mapper.readValue(input, Map.class);
        } catch (Exception exception) {
            HttpExchangeResponses.writeJson(exchange, 400, Map.of("success", false, "error", "invalid json"), mapper);
            return;
        }

        byte[] frameBytes;
        String frameSha = null;
        try {
            frameBytes = decodeFrame(payload);
            frameSha = sha256Hex(frameBytes);
        } catch (Exception exception) {
            HttpExchangeResponses.writeJson(exchange, 400, Map.of("success", false, "error", exception.getMessage()), mapper);
            return;
        }

        String source = String.valueOf(payload.getOrDefault("source", payload.getOrDefault("streamId", "unknown")));
        Object frameIndex = payload.get("frameIndex");
        Object timestamp = payload.get("timestamp");

        List<Detection> detections;
        try {
            detections = detector.detect(frameBytes);
        } catch (Exception exception) {
            HttpExchangeResponses.writeJson(exchange, 500, Map.of("success", false, "error", exception.getMessage()), mapper);
            return;
        }

        detectionsTotal += detections.size();
        boolean alertSent = false;
        String alertError = null;
        if (!detections.isEmpty() && cooldowns.allow(source, Instant.now().getEpochSecond())) {
            Map<String, Object> alert = Map.of(
                    "event", "person_detected",
                    "source", source,
                    "frameIndex", frameIndex,
                    "timestamp", timestamp,
                    "frameSha256", frameSha,
                    "personCount", detections.size(),
                    "boxes", detections
            );
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(alertUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(alert)))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    alertSent = true;
                    alertsSentTotal++;
                }
            } catch (Exception exception) {
                alertError = exception.getMessage();
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("source", source);
        response.put("frameIndex", frameIndex);
        response.put("timestamp", timestamp);
        response.put("personCount", detections.size());
        response.put("alertSent", alertSent);
        response.put("alertError", alertError);
        response.put("boxes", detections);
        HttpExchangeResponses.writeJson(exchange, 200, response, mapper);
    }

    private byte[] decodeFrame(Map<String, Object> payload) {
        Object encoded = payload.get("frameBase64");
        if (encoded == null) {
            throw new IllegalArgumentException("frameBase64 missing");
        }
        byte[] frame = Base64.getDecoder().decode(String.valueOf(encoded));
        String expectedSha = String.valueOf(payload.getOrDefault("frameSha256", ""));
        if (!expectedSha.isBlank() && !expectedSha.equals(sha256Hex(frame))) {
            throw new IllegalArgumentException("frame hash mismatch");
        }
        return frame;
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash frame.", exception);
        }
    }

}
