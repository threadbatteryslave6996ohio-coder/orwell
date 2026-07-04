package dev.orwell.bucket.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class FileAuditLogger {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path logFile;

    public FileAuditLogger(ProxyProperties properties) throws IOException {
        this.logFile = Path.of(properties.logging().auditFile());
        Path parent = this.logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public synchronized void write(String event, Map<String, Object> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("event", event);
        payload.putAll(fields);

        try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                Files.exists(logFile) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE)) {
            writer.write(objectMapper.writeValueAsString(payload));
            writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write audit log.", exception);
        }
    }
}
