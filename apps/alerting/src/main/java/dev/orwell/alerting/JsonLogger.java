package dev.orwell.alerting;

import dev.orwell.logging.JsonLineFileWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

final class JsonLogger {
    private final JsonLineFileWriter writer;

    JsonLogger(Path logFile) throws IOException {
        this.writer = new JsonLineFileWriter(logFile);
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
        writer.write(payload);
    }
}
