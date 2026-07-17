package dev.orwell.clients.core;

import java.nio.file.Path;

/** Where the desktop clients keep clipboard entries they could not send. */
public final class OfflineLogPath {
    /** Offline clipboard log the clients read and write. */
    public static final Path DEFAULT = Path.of("klippy-offline-clipboard.json");

    private OfflineLogPath() {
    }

    /**
     * Dead-letter log sibling for {@code offlineLog}: {@code x.json} becomes
     * {@code x-dead-letter.json}.
     */
    public static Path deadLetterFor(Path offlineLog) {
        String fileName = offlineLog.getFileName() == null ? "offline" : offlineLog.getFileName().toString();
        int extension = fileName.lastIndexOf('.');
        String deadLetterName = extension > 0
                ? fileName.substring(0, extension) + "-dead-letter" + fileName.substring(extension)
                : fileName + "-dead-letter.json";
        return offlineLog.toAbsolutePath().normalize().resolveSibling(deadLetterName);
    }
}
