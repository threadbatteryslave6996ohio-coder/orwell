package dev.orwell.backup;

import dev.orwell.backup.storage.DirectoryStorage;
import dev.orwell.backup.storage.StorageBackup;
import dev.orwell.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class BackupRunner {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** A dump that has not finished by now is wedged, not slow; nothing here runs for hours. */
    private static final int DUMP_TIMEOUT_MINUTES = 60;

    /** The only filenames retention will delete. Anything else in that directory is not ours. */
    private static final String DUMP_SUFFIX = ".dump";

    /** How much of pg_dump's output reaches the exception message. Enough to diagnose, not a log dump. */
    private static final int ERROR_EXCERPT_CHARS = 4000;

    private final Path outputDir;
    private final int retentionDays;
    private final Logger logger;

    public BackupRunner(Path outputDir, int retentionDays, Logger logger) {
        this.outputDir = outputDir;
        this.retentionDays = retentionDays;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void run(List<ProjectConfig> projects) throws Exception {
        Files.createDirectories(outputDir);
        for (var project : projects) {
            dump(project);
        }
    }

    /**
     * Deletes this app's own expired dumps, in every directory this app writes them to.
     *
     * <p>Takes the projects rather than sweeping {@link #outputDir} alone, because a project with
     * {@code BACKUP_<NAME>_STORAGE_DIR} set writes somewhere else entirely, and sweeping only the
     * output directory left those dumps to accumulate forever while this method still reported
     * success — retention that silently does not apply is worse than no retention.
     *
     * <p><b>The scope is deliberately narrow, and must stay narrow.</b> A storage directory is an
     * operator's path — the documented case is a separate mount such as a NAS — so it may hold
     * things this app did not put there. Retention therefore touches only files ending in
     * {@code .dump}, only directly inside the per-project directory {@link DirectoryStorage}
     * creates, and never recurses. A recursive sweep of every regular file under an operator's
     * directory deletes their data on the first run, which is a far worse bug than the one this
     * method was widened to fix.
     */
    public void cleanUp(List<ProjectConfig> projects) throws IOException {
        if (retentionDays <= 0) {
            return;
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86400000L;
        for (Path directory : backupDirectories(projects)) {
            if (!Files.isDirectory(directory)) {
                continue;
            }
            // list, not walk: one level, and only inside a directory this app owns.
            try (var files = Files.list(directory)) {
                for (var file : files.toList()) {
                    if (Files.isRegularFile(file)
                            && file.getFileName().toString().endsWith(DUMP_SUFFIX)
                            && Files.getLastModifiedTime(file).toMillis() < cutoff) {
                        Files.delete(file);
                    }
                }
            }
        }
    }

    /**
     * Removes a per-project directory this app created once it holds nothing.
     *
     * <p>Only those directories, for the same reason as above: the operator's storage directory
     * itself is never a candidate, and no tree is walked looking for empty ones.
     */
    public void cleanUpEmptyDirs(List<ProjectConfig> projects) throws IOException {
        for (ProjectConfig project : projects) {
            Path projectDir = projectDirectory(project);
            if (!Files.isDirectory(projectDir)) {
                continue;
            }
            try (var entries = Files.list(projectDir)) {
                if (entries.findAny().isEmpty()) {
                    Files.delete(projectDir);
                }
            }
        }
    }

    /**
     * Every directory this app writes dumps into: each project's own directory, plus the output
     * directory itself, where {@link #dump} stages a temp file that a crash can leave behind.
     */
    private Set<Path> backupDirectories(List<ProjectConfig> projects) {
        Set<Path> directories = new LinkedHashSet<>();
        directories.add(outputDir.toAbsolutePath().normalize());
        for (ProjectConfig project : projects) {
            directories.add(projectDirectory(project));
        }
        return directories;
    }

    /** Where {@link DirectoryStorage} puts this project's dumps. */
    private Path projectDirectory(ProjectConfig project) {
        String configured = project.storageDir();
        Path base = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : outputDir;
        return base.resolve(DirectoryStorage.projectDirectoryName(project.name()))
                .toAbsolutePath().normalize();
    }

    private void dump(ProjectConfig project) throws Exception {
        String filename = "%s_%s.dump".formatted(
                project.name(), TIMESTAMP.format(LocalDateTime.now()));
        Path temp = outputDir.resolve(filename);
        // pg_dump's diagnostics go to a file, not to a pipe this process must remember to drain.
        // Reading the pipe only after waitFor() deadlocks the moment the output exceeds the OS pipe
        // buffer (~64 KiB): the child blocks writing, the parent blocks waiting, and a backup that
        // hangs forever looks exactly like one that is merely slow. A file write never blocks, so
        // the timeout below is reachable and this cannot wedge.
        Path dumpLog = Files.createTempFile("pg_dump-" + DirectoryStorage.projectDirectoryName(project.name()) + "-", ".log");

        ProcessBuilder pb = new ProcessBuilder(
                "pg_dump",
                "-d", project.databaseUrl(),
                "-F", "c",
                "-f", temp.toString()
        );
        pb.redirectErrorStream(true);
        pb.redirectOutput(dumpLog.toFile());

        logger.info("Starting project backup.", Map.of("project", project.name()));

        try {
            Process process = pb.start();
            if (!process.waitFor(DUMP_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                deleteQuietly(temp);
                throw new IOException("pg_dump for %s did not finish within %d minutes".formatted(
                        project.name(), DUMP_TIMEOUT_MINUTES));
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                String error = excerpt(dumpLog);
                deleteQuietly(temp);
                throw new IOException("pg_dump failed for %s (exit %d): %s".formatted(
                        project.name(), exitCode, error));
            }
        } finally {
            deleteQuietly(dumpLog);
        }

        StorageBackup storage = resolveStorage(project);
        Path stored = storage.store(project.name(), temp);

        logger.info("Backup saved.", Map.of("project", project.name(), "path", stored.toString()));
    }

    /** The tail of pg_dump's output — the end is where the reason is. */
    private static String excerpt(Path dumpLog) {
        try {
            String output = Files.readString(dumpLog).strip();
            if (output.length() <= ERROR_EXCERPT_CHARS) {
                return output;
            }
            return "…" + output.substring(output.length() - ERROR_EXCERPT_CHARS);
        } catch (IOException unreadable) {
            return "(pg_dump output could not be read: " + unreadable + ")";
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
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
