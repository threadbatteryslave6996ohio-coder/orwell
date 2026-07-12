package dev.orwell.logging;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public final class JsonLineFileWriter {
    private final ObjectMapper objectMapper;
    private final Path logFile;

    public JsonLineFileWriter(Path logFile) throws IOException {
        this(logFile, new ObjectMapper());
    }

    public JsonLineFileWriter(Path logFile, ObjectMapper objectMapper) throws IOException {
        this.logFile = logFile;
        this.objectMapper = objectMapper;
        Path parent = logFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public synchronized void write(Map<String, Object> payload) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                Files.exists(logFile)
                        ? java.nio.file.StandardOpenOption.APPEND
                        : java.nio.file.StandardOpenOption.CREATE
        )) {
            writer.write(objectMapper.writeValueAsString(payload));
            writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write JSON log entry.", exception);
        }
    }
}
