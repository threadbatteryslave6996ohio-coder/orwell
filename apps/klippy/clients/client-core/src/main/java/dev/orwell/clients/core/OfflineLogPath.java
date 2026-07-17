package dev.orwell.clients.core;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Where the desktop clients keep clipboard entries they could not send, and the pre-rename
 * location they migrate from.
 *
 * <p>The file used to be {@code clippy-offline-clipboard.json}. A client that simply started
 * reading the new name would leave a user's unsynced entries sitting in the old file, unread and
 * unrecoverable — no error, just a clipboard history that quietly went missing. So the clients
 * migrate the old file forward on startup instead.
 */
public final class OfflineLogPath {
    /** Offline clipboard log the clients read and write. */
    public static final Path DEFAULT = Path.of("klippy-offline-clipboard.json");

    /**
     * Pre-rename name, kept only so an existing file migrates forward. Safe to delete once every
     * deployed client has started at least once on a version containing this migration.
     */
    public static final Path LEGACY = Path.of("clippy-offline-clipboard.json");

    private OfflineLogPath() {
    }

    /**
     * Moves a pre-rename offline log, and its dead-letter sibling, to the current names. A no-op
     * when there is nothing to migrate or the current file already exists, so it is safe to call
     * on every startup.
     *
     * <p>Only applies to the default location: a client given an explicit path is pointed at a
     * file the caller chose, and nothing should be moved out from under it.
     */
    public static void migrateLegacyIfPresent() {
        migrateLegacyIfPresent(LEGACY, DEFAULT);
    }

    static void migrateLegacyIfPresent(Path legacy, Path current) {
        migrate(legacy, current);
        migrate(deadLetterFor(legacy), deadLetterFor(current));
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

    private static void migrate(Path legacy, Path current) {
        if (Files.exists(current) || !Files.exists(legacy)) {
            return;
        }
        try {
            move(legacy, current);
            System.err.printf("Migrated offline clipboard log %s -> %s%n",
                    legacy.toAbsolutePath(), current.toAbsolutePath());
        } catch (IOException exception) {
            // Losing the migration is recoverable -- the old file is still on disk and can be
            // passed explicitly -- so warn and carry on rather than refusing to start.
            System.err.printf("Could not migrate offline clipboard log %s -> %s: %s. "
                            + "Entries in the old file will not be synchronized until it is moved.%n",
                    legacy.toAbsolutePath(), current.toAbsolutePath(), exception.getMessage());
        }
    }

    private static void move(Path legacy, Path current) throws IOException {
        try {
            Files.move(legacy, current, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(legacy, current);
        }
    }
}
