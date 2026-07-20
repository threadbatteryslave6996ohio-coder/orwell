package dev.orwell.logging;

import java.util.Map;

/**
 * The logging service every app depends on. Implementations decide the sink — console, file,
 * JSON lines, database — and callers only ever see this interface, so the sink can be swapped
 * without touching a call site.
 *
 * <p>{@link #log(LogEntry)} stays the single abstract method: the convenience overloads below are
 * defaults, so this remains a functional interface and a test double can still be a lambda.
 */
@FunctionalInterface
public interface Logger {
    void log(LogEntry entry);

    default void log(LogLevel level, String message, Map<String, Object> metadata) {
        log(new LogEntry(level, message, metadata));
    }

    default void trace(String message) {
        log(LogLevel.TRACE, message, Map.of());
    }

    default void trace(String message, Map<String, Object> metadata) {
        log(LogLevel.TRACE, message, metadata);
    }

    default void debug(String message) {
        log(LogLevel.DEBUG, message, Map.of());
    }

    default void debug(String message, Map<String, Object> metadata) {
        log(LogLevel.DEBUG, message, metadata);
    }

    default void info(String message) {
        log(LogLevel.INFO, message, Map.of());
    }

    default void info(String message, Map<String, Object> metadata) {
        log(LogLevel.INFO, message, metadata);
    }

    default void warn(String message) {
        log(LogLevel.WARN, message, Map.of());
    }

    default void warn(String message, Map<String, Object> metadata) {
        log(LogLevel.WARN, message, metadata);
    }

    default void error(String message) {
        log(LogLevel.ERROR, message, Map.of());
    }

    default void error(String message, Map<String, Object> metadata) {
        log(LogLevel.ERROR, message, metadata);
    }
}
