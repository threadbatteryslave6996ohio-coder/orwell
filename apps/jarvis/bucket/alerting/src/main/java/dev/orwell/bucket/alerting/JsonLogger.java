package dev.orwell.bucket.alerting;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonLogger {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path logFile;

    JsonLogger(Path logFile) throws IOException {
        this.logFile = logFile;
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    synchronized void info(String message, Map<String, Object> extra) {
        write("INFO", message, extra, null);
    }

    synchronized void error(String message, Map<String, Object> extra, Throwable error) {
        write("ERROR", message, extra, error);
    }

    private void write(String level, String message, Map<String, Object> extra, Throwable error) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("level", level);
        payload.put("message", message);
        if (extra != null && !extra.isEmpty()) {
            payload.put("fields", extra);
        }
        if (error != null) {
            payload.put("error", error.toString());
        }
        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                Files.exists(logFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
            writer.write(objectMapper.writeValueAsString(payload));
            writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write alert log.", exception);
        }
    }
}
