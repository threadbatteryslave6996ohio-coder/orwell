package dev.orwell.logging;

import dev.orwell.primitives.NonEmptyString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.Map;
import java.util.Objects;

public final class CustomLogger implements Logger {
    private final String name;

    /** @deprecated call {@link LogFiles#configureDirectoryFromLogFile(String)} directly. */
    @Deprecated
    public static void configureDirectoryFromLogFile(String logFileName) {
        LogFiles.configureDirectoryFromLogFile(logFileName);
    }

    public CustomLogger(String name) {
        this.name = new NonEmptyString(name, "name cannot be blank").value();
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        log(entry.level(), entry.message(), entry.metadata());
    }

    public void log(LogLevel level, String message, Map<String, Object> metadata) {
        String formatted = formatEntry(level, message, metadata);
        writeToFile(formatted);
    }

    public void log(String message) {
        String normalizedMessage = message == null ? "" : message.trim();
        String entry = formatEntry(LogLevel.INFO, normalizedMessage, Map.of());
        writeToFile(entry);
    }

    /** Synchronized here rather than per-overload so every entry point is serialized. */
    private synchronized void writeToFile(String entry) {
        Path logPath = logPath();
        try {
            Path parent = logPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (Files.exists(logPath) && Files.size(logPath) > 0) {
                Files.writeString(logPath, "\n-----\n" + entry, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.APPEND);
            } else {
                Files.writeString(logPath, entry, StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot write custom logger output.", exception);
        }
    }

    private Path logPath() {
        return LogFiles.resolve(name, ".txt");
    }

    private String formatEntry(LogLevel level, String message, Map<String, Object> metadata) {
        return LogFormatter.format(name, level, message, metadata) + "\n";
    }
}
