package dev.orwell.backup.storage;

import java.io.IOException;
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
        Path projectDir = baseDirectory.resolve(sanitize(projectName));
        Files.createDirectories(projectDir);
        Path target = projectDir.resolve(dumpFile.getFileName());
        Files.move(dumpFile, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
