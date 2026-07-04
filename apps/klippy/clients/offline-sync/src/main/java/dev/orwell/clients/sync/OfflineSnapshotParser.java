package dev.orwell.clients.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import dev.orwell.clients.core.ClipboardJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the offline clipboard JSON file into a {@link ClipboardSnapshot}. Malformed entries and
 * unsupported record types are captured as {@link RejectedRecord}s rather than aborting the parse, so a
 * single bad entry never blocks the rest of the file from synchronizing.
 */
final class OfflineSnapshotParser {
    private static final OfflineSnapshotParser DEFAULT = new OfflineSnapshotParser(ClipboardJson.mapper());

    private final ObjectMapper json;

    OfflineSnapshotParser(ObjectMapper json) {
        this.json = json;
    }

    static ClipboardSnapshot parseSnapshot(String content, Path path) {
        return DEFAULT.parse(content, path);
    }

    static List<ClipboardRecord> parseRecords(String content, Path path) throws IOException {
        ClipboardSnapshot snapshot = DEFAULT.parse(content, path);
        if (!snapshot.rejections().isEmpty()) {
            throw new IOException(snapshot.rejections().getFirst().reason());
        }
        return snapshot.records();
    }

    ClipboardSnapshot parse(String content, Path path) {
        JsonNode root;
        try {
            root = json.readTree(content);
        } catch (IOException exception) {
            return new ClipboardSnapshot(content, List.of(), List.of(
                    new RejectedRecord(
                            "invalid-json", "Offline clipboard file is not valid JSON", content)));
        }
        if (!(root instanceof ArrayNode array)) {
            return new ClipboardSnapshot(content, List.of(), List.of(
                    new RejectedRecord(
                            "invalid-json",
                            "Offline clipboard file must contain a JSON array: " + path.toAbsolutePath(),
                            root == null ? "null" : root.toString())));
        }

        List<ClipboardRecord> records = new ArrayList<>();
        List<RejectedRecord> rejections = new ArrayList<>();
        for (int index = 0; index < array.size(); index++) {
            JsonNode node = array.get(index);
            String type = node.path("type").asText("");
            if ("auth".equals(type)) {
                continue;
            }
            String context = "offline entry " + index;
            if (!type.isEmpty() && !"clipboard".equals(type)) {
                rejections.add(new RejectedRecord(
                        "unknown-type", context + ": Unsupported record type '" + type + "'", node.toString()));
                continue;
            }
            try {
                records.add(new ClipboardRecord(
                        ClipboardJson.requiredText(node, "clientId"),
                        ClipboardJson.requiredText(node, "content"),
                        ClipboardJson.parseTimestamp(ClipboardJson.requiredText(node, "timestamp"), context)));
            } catch (IOException exception) {
                rejections.add(new RejectedRecord(
                        "invalid-entry", context + ": " + exception.getMessage(), node.toString()));
            }
        }
        return new ClipboardSnapshot(content, records, rejections);
    }
}
