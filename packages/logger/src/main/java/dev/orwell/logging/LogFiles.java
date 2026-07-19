package dev.orwell.logging;

import java.nio.file.Path;

/**
 * Resolves where file-backed sinks write. Every server derives this from one configured value —
 * {@code logging.file.name}, which the bootstrap turns into the {@code custom.logger.dir} system
 * property — so the app's own log file lands beside Spring's.
 */
public final class LogFiles {
    static final String LOG_DIRECTORY_PROPERTY = "custom.logger.dir";
    private static final Path DEFAULT_DIRECTORY = Path.of("logs");

    private LogFiles() {
    }

    /**
     * Points file sinks at the directory holding {@code logFileName}. A blank name, or one with no
     * parent directory, falls back to the working directory.
     */
    public static void configureDirectoryFromLogFile(String logFileName) {
        Path loggingPath = Path.of(logFileName == null ? "" : logFileName.trim());
        Path parentDirectory = loggingPath.getParent();
        String directory = parentDirectory == null ? Path.of(".").toString() : parentDirectory.toString();
        System.setProperty(LOG_DIRECTORY_PROPERTY, directory);
    }

    public static Path directory() {
        String configured = System.getProperty(LOG_DIRECTORY_PROPERTY);
        return configured == null || configured.trim().isEmpty()
                ? DEFAULT_DIRECTORY
                : Path.of(configured.trim());
    }

    /** Resolves {@code <directory>/<name><extension>}, e.g. {@code logs/auth-server.jsonl}. */
    public static Path resolve(String name, String extension) {
        return directory().resolve(name + extension).normalize();
    }
}
