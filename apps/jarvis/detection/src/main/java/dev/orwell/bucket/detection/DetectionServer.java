package dev.orwell.bucket.detection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

    static DetectionServer fromEnvironment() {
        String host = getenv("DETECTION_SERVER_HOST", "127.0.0.1");
        int port = Integer.parseInt(getenv("DETECTION_SERVER_PORT", "9001"));
        String alertUrl = getenv("DETECTION_ALERT_URL", "http://127.0.0.1:9000/alerts");
        int cooldownSeconds = Integer.parseInt(getenv("DETECTION_ALERT_COOLDOWN_SECONDS", "60"));
        double minConfidence = Double.parseDouble(getenv("DETECTION_MIN_CONFIDENCE", "0.0"));
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
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("success", false, "error", "method not allowed"));
            return;
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("status", "healthy");
        response.put("detectionsTotal", detectionsTotal);
        response.put("alertsSentTotal", alertsSentTotal);
        writeJson(exchange, 200, response);
    }

    private void detect(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            writeJson(exchange, 405, Map.of("success", false, "error", "method not allowed"));
            return;
        }
        Map<String, Object> payload;
        try (InputStream input = exchange.getRequestBody()) {
            payload = mapper.readValue(input, Map.class);
        } catch (Exception exception) {
            writeJson(exchange, 400, Map.of("success", false, "error", "invalid json"));
            return;
        }

        byte[] frameBytes;
        String frameSha = null;
        try {
            frameBytes = decodeFrame(payload);
            frameSha = sha256Hex(frameBytes);
        } catch (Exception exception) {
            writeJson(exchange, 400, Map.of("success", false, "error", exception.getMessage()));
            return;
        }

        String source = String.valueOf(payload.getOrDefault("source", payload.getOrDefault("streamId", "unknown")));
        Object frameIndex = payload.get("frameIndex");
        Object timestamp = payload.get("timestamp");

        List<Detection> detections;
        try {
            detections = detector.detect(frameBytes);
        } catch (Exception exception) {
            writeJson(exchange, 500, Map.of("success", false, "error", exception.getMessage()));
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
        writeJson(exchange, 200, response);
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

    private void writeJson(HttpExchange exchange, int status, Map<String, ?> payload) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
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

    private static String getenv(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
