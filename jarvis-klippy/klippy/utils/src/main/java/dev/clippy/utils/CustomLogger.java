package dev.clippy.utils;

import dev.clippy.utils.Strings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CustomLogger {
    private static final String LOG_DIRECTORY_PROPERTY = "custom.logger.dir";
    private static final Path LOG_DIRECTORY = Path.of("logs");

    private final String name;

    /**
     * Points the custom logger at the directory that holds {@code logFileName}. The server and client
     * bootstrap classes all derive {@code custom.logger.dir} from their configured logging file the same
     * way; this consolidates that logic. A blank file name, or one with no parent directory, falls back
     * to the working directory.
     */
    public static void configureDirectoryFromLogFile(String logFileName) {
        Path loggingPath = Path.of(logFileName == null ? "" : logFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String directory = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty(LOG_DIRECTORY_PROPERTY, directory);
    }

    public CustomLogger(String name) {
        this.name = Strings.requireNonBlank(name, "name");
    }

    public synchronized void log(String message) {
        String normalizedMessage = message == null ? "" : message.trim();
        String entry = formatEntry(normalizedMessage);
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

    private String formatEntry(String message) {
        return "[" + name + "] " + message + "\n";
    }
}
