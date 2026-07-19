package dev.orwell.logging;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Writes entries as one JSON object per line. Use this where logs are meant to be machine-read;
 * {@link CustomLogger} and {@link ConsoleLogger} are the human-readable sinks.
 *
 * <p>Each line carries {@code timestamp}, {@code level}, {@code message}, and the entry's metadata
 * flattened alongside them. Metadata keys that collide with the three reserved names are dropped
 * rather than allowed to overwrite them.
 */
public final class JsonLogger implements Logger {
    private final JsonLineFileWriter writer;

    public JsonLogger(Path logFile) throws IOException {
        this(new JsonLineFileWriter(logFile));
    }

    public JsonLogger(JsonLineFileWriter writer) {
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    @Override
    public void log(LogEntry entry) {
        Objects.requireNonNull(entry, "entry");
        writer.write(LogEntryJson.payload(entry, Instant.now()));
    }
}
