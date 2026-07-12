package dev.orwell.bucket.proxy;

import dev.orwell.logging.JsonLineFileWriter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FileAuditLogger {
    private final JsonLineFileWriter writer;

    public FileAuditLogger(ProxyProperties properties) throws IOException {
        this.writer = new JsonLineFileWriter(Path.of(properties.logging().auditFile()));
    }

    public synchronized void write(String event, Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("event", event);
        payload.putAll(fields);
        writer.write(payload);
    }
}
