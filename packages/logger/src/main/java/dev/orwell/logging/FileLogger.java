package dev.orwell.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.orwell.primitives.NonEmptyString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class FileLogger implements Logger {
    private static final String LOG_DIRECTORY_PROPERTY = "custom.logger.dir";
    private static final Path LOG_DIRECTORY = Path.of("logs");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.INDENT_OUTPUT);

    private final String name;

    public static void configureDirectoryFromLogFile(String logFileName) {
        Path loggingPath = Path.of(logFileName == null ? "" : logFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String directory = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty(LOG_DIRECTORY_PROPERTY, directory);
    }

    public FileLogger(String name) {
        this.name = new NonEmptyString(name, "name cannot be blank").value();
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        log(formatEntry(entry));
    }

    private void log(String formattedEntry) {
        Path logPath = logPath();
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(logPath) && Files.size(logPath) > 0) {
                Files.writeString(logPath, "\n-----\n" + formattedEntry, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(logPath, formattedEntry, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write custom logger output.", exception);
        }
    }

    private String formatEntry(LogEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(name).append("] ");
        sb.append("[").append(entry.level()).append("] ");
        sb.append(entry.message());
        if (!entry.metadata().isEmpty()) {
            try {
                sb.append("\n").append(OBJECT_MAPPER.writeValueAsString(entry.metadata()));
            } catch (JsonProcessingException e) {
                sb.append("\n").append(entry.metadata());
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    private String nameToFileName() {
        return name + ".txt";
    }

    private Path logPath() {
        String configuredDirectory = System.getProperty(LOG_DIRECTORY_PROPERTY);
        Path directory = configuredDirectory == null || configuredDirectory.trim().isEmpty()
                ? LOG_DIRECTORY
                : Path.of(configuredDirectory.trim());
        return directory.resolve(nameToFileName()).normalize();
    }
}
