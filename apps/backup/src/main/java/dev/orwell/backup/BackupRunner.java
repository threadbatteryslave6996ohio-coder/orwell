package dev.orwell.backup;

import dev.orwell.backup.storage.DirectoryStorage;
import dev.orwell.backup.storage.StorageBackup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class BackupRunner {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path outputDir;
    private final int retentionDays;

    public BackupRunner(Path outputDir, int retentionDays) {
        this.outputDir = outputDir;
        this.retentionDays = retentionDays;
    }

    public void run(List<ProjectConfig> projects) throws Exception {
        Files.createDirectories(outputDir);
        for (var project : projects) {
            dump(project);
        }
    }

    public void cleanUp() throws IOException {
        if (retentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86400000L;
        try (var files = Files.walk(outputDir)) {
            for (var file : files.toList()) {
                if (Files.isRegularFile(file) && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                    Files.delete(file);
                }
            }
        }
    }

    public void cleanUpEmptyDirs() throws IOException {
        if (!Files.isDirectory(outputDir)) {
            return;
        }
        try (var dirs = Files.walk(outputDir)) {
            var list = dirs.sorted((a, b) -> b.toString().length() - a.toString().length()).toList();
            for (var dir : list) {
                if (Files.isDirectory(dir) && !dir.equals(outputDir)) {
                    try (var entries = Files.list(dir)) {
                        if (entries.findAny().isEmpty()) {
                            Files.delete(dir);
                        }
                    }
                }
            }
        }
    }

    private void dump(ProjectConfig project) throws Exception {
        String filename = "%s_%s.dump".formatted(
                project.name(), TIMESTAMP.format(LocalDateTime.now()));
        Path temp = outputDir.resolve(filename);

        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-d", project.databaseUrl(),
                "-F", "c",
                "-f", temp.toString()
        );
        pb.redirectErrorStream(true);

        System.out.printf("[%s] Starting backup ...%n", project.name());

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String error = new String(process.getInputStream().readAllBytes());
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
            }
            throw new IOException("pg_dump failed for %s (exit %d): %s".formatted(
                    project.name(), exitCode, error));
        }

        StorageBackup storage = resolveStorage(project);
        Path stored = storage.store(project.name(), temp);

        System.out.printf("[%s] Backup saved to %s%n", project.name(), stored);
    }

    private StorageBackup resolveStorage(ProjectConfig project) {
        return switch (project.storageType()) {
            case "directory" -> {
                Path dir = project.storageDir() != null && !project.storageDir().isBlank()
                        ? Path.of(project.storageDir())
                        : outputDir;
                yield new DirectoryStorage(dir);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown storage type: " + project.storageType() + " for project: " + project.name());
        };
    }
}
