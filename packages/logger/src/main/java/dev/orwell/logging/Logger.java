package dev.orwell.logging;

@FunctionalInterface
public interface Logger {
    void log(LogEntry entry);
}
