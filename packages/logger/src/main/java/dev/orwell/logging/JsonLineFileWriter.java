package dev.orwell.logging;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    /**
     * Opens with CREATE and APPEND together, in one atomic call. Choosing between the two from a
     * preceding {@code Files.exists} check is wrong in both directions: CREATE alone neither
     * truncates nor seeks to the end, so it overwrites the start of an existing file, and APPEND
     * alone throws if the file is gone. A log rotator renaming the file between the check and the
     * open hits exactly those windows.
     */
    public synchronized void write(Map<String, Object> payload) {
        try (BufferedWriter writer = Files.newBufferedWriter(
                logFile,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
        )) {
            writer.write(objectMapper.writeValueAsString(payload));
            writer.newLine();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write JSON log entry.", exception);
        }
    }
}
