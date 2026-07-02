package dev.clippy.clients.sync;

import java.util.List;

/**
 * Immutable view of the offline clipboard file at a point in time: its raw {@code content}, the
 * clipboard {@code records} parsed from it, and any {@code rejections} that could not be parsed.
 */
record ClipboardSnapshot(String content, List<ClipboardRecord> records, List<RejectedRecord> rejections) {
    ClipboardSnapshot {
        records = List.copyOf(records);
        rejections = List.copyOf(rejections);
    }

    ClipboardSnapshot(String content, List<ClipboardRecord> records) {
        this(content, records, List.of());
    }
}
