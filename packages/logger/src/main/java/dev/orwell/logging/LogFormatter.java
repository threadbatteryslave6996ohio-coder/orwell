package dev.orwell.logging;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Shared rendering for the text-based loggers so console and file output stay identical. Metadata
 * keys are sorted so a given entry always renders the same way, which keeps log diffing useful.
 */
final class LogFormatter {
    private LogFormatter() {
    }

    static String format(String name, LogLevel level, String message, Map<String, Object> metadata) {
        StringBuilder builder = new StringBuilder()
                .append(Instant.now())
                .append(" [").append(name).append("] [").append(level).append("] ")
                .append(message == null ? "" : message);
        appendMetadata(builder, metadata);
        return builder.toString();
    }

    private static void appendMetadata(StringBuilder builder, Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        // Comparator.nullsFirst rather than TreeMap's natural ordering: a null key would otherwise
        // throw from inside the sort, which is the same way a null value used to break logging.
        Map<String, Object> sorted = new TreeMap<>(Comparator.nullsFirst(Comparator.naturalOrder()));
        sorted.putAll(metadata);
        sorted.forEach((key, value) ->
                builder.append(' ').append(key).append('=').append(renderValue(value)));
    }

    /**
     * Values are quoted when they contain whitespace, {@code =}, or a quote, so a field like
     * {@code error=Connection refused: connect} stays one parseable field instead of reading as
     * three. Null renders as a bare {@code null} rather than blowing up the line.
     */
    private static String renderValue(Object value) {
        if (value == null) {
            return "null";
        }
        String text = String.valueOf(value);
        if (!needsQuoting(text)) {
            return text;
        }
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static boolean needsQuoting(String text) {
        if (text.isEmpty()) {
            return true;
        }
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (Character.isWhitespace(character) || character == '=' || character == '"' || character == '\\') {
                return true;
            }
        }
        return false;
    }
}
