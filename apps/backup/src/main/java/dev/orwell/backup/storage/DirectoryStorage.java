package dev.orwell.backup.storage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class DirectoryStorage implements StorageBackup {
    private final Path baseDirectory;

    public DirectoryStorage(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    @Override
    public Path store(String projectName, Path dumpFile) throws IOException {
        Path projectDir = baseDirectory.resolve(projectDirectoryName(projectName));
        Files.createDirectories(projectDir);
        Path target = projectDir.resolve(dumpFile.getFileName());
        try {
            Files.move(dumpFile, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException differentFilesystem) {
            // The dump is written under BACKUP_OUTPUT_DIR and stored under this directory, and a
            // per-project BACKUP_<NAME>_STORAGE_DIR is usually a separate mount — a NAS, a bigger
            // disk. That is the normal reason to configure one, and an atomic move cannot cross a
            // filesystem boundary, so insisting on one failed *after* the dump had been paid for.
            // Copy-and-delete instead: no longer atomic, which costs a partial file if the process
            // dies mid-copy, and a partial file on the next run is cheaper than never storing one.
            Files.move(dumpFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * The subdirectory a project's dumps live in.
     *
     * <p>Public because retention has to sweep exactly this directory and nothing around it — two
     * copies of the rule would let the sweep and the writer disagree about where dumps are, and the
     * sweep would then either miss them or reach outside.
     */
    public static String projectDirectoryName(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
