package dev.orwell.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Retention: what it must delete, and — more important — what it must not touch.
 *
 * <p>{@code cleanUp} used to walk {@code BACKUP_OUTPUT_DIR} alone while {@code resolveStorage} was
 * free to send a project to {@code BACKUP_<NAME>_STORAGE_DIR}. Those dumps were never swept and the
 * run still logged "Cleaning up expired backups", so the failure was invisible: the setting was
 * configured, the log said it ran, and the disk filled anyway.
 *
 * <p>The fix for that is what the second half of this class exists to bound. A storage directory is
 * an operator's path — the documented case is a separate mount such as a NAS — so widening the
 * sweep to reach it without narrowing <em>what</em> it deletes would destroy unrelated data on the
 * first run. That would be a far worse bug than the one being fixed, which is why these tests are
 * here rather than left to review.
 */
class BackupRunnerRetentionTest {
    private static final dev.orwell.logging.Logger NO_OP = entry -> {
    };

    @Test
    void deletesExpiredDumpsInTheDefaultProjectDirectory(@TempDir Path output) throws Exception {
        Path old = aged(output.resolve("klippy").resolve("klippy_20200101_000000.dump"), 40);
        Path fresh = aged(output.resolve("klippy").resolve("klippy_20260101_000000.dump"), 1);

        new BackupRunner(output, 30, NO_OP).cleanUp(List.of(project("klippy", null)));

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    /** The gap this widening closed: a project with its own storage directory is swept too. */
    @Test
    void deletesExpiredDumpsInAProjectsOwnStorageDirectory(@TempDir Path output, @TempDir Path nas)
            throws Exception {
        Path old = aged(nas.resolve("klippy").resolve("klippy_20200101_000000.dump"), 40);
        Path fresh = aged(nas.resolve("klippy").resolve("klippy_20260101_000000.dump"), 1);

        new BackupRunner(output, 30, NO_OP).cleanUp(List.of(project("klippy", nas.toString())));

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(fresh));
    }

    /** A stale staging file from a crashed run is ours, and is swept. */
    @Test
    void deletesAnExpiredStagedDumpLeftInTheOutputDirectory(@TempDir Path output) throws Exception {
        Path stranded = aged(output.resolve("klippy_20200101_000000.dump"), 40);

        new BackupRunner(output, 30, NO_OP).cleanUp(List.of(project("klippy", null)));

        assertFalse(Files.exists(stranded));
    }

    // ───────────────────────────────────────────────── what retention must never reach

    /** The operator's mount is not ours to walk. Only the directory we created inside it. */
    @Test
    void neverTouchesTheOperatorsOwnFilesInAStorageDirectory(@TempDir Path output, @TempDir Path nas)
            throws Exception {
        Path theirArchive = aged(nas.resolve("tax-returns-2019.tar.gz"), 4000);
        Path theirNested = aged(nas.resolve("photos").resolve("wedding.jpg"), 4000);
        Path ourExpiredDump = aged(nas.resolve("klippy").resolve("klippy_20200101_000000.dump"), 40);

        new BackupRunner(output, 30, NO_OP).cleanUp(List.of(project("klippy", nas.toString())));

        assertTrue(Files.exists(theirArchive), "a file beside the project directory is not ours");
        assertTrue(Files.exists(theirNested), "retention must not recurse into an operator's tree");
        assertFalse(Files.exists(ourExpiredDump));
    }

    /** Even inside our own directory, only our own filenames. */
    @Test
    void deletesOnlyDumpFilesInsideTheProjectDirectory(@TempDir Path output, @TempDir Path nas)
            throws Exception {
        Path note = aged(nas.resolve("klippy").resolve("README.txt"), 4000);
        Path dump = aged(nas.resolve("klippy").resolve("klippy_20200101_000000.dump"), 40);

        new BackupRunner(output, 30, NO_OP).cleanUp(List.of(project("klippy", nas.toString())));

        assertTrue(Files.exists(note));
        assertFalse(Files.exists(dump));
    }

    @Test
    void keepsEverythingWhenRetentionIsDisabled(@TempDir Path output, @TempDir Path nas)
            throws Exception {
        Path old = aged(nas.resolve("klippy").resolve("klippy_20200101_000000.dump"), 400);

        new BackupRunner(output, 0, NO_OP).cleanUp(List.of(project("klippy", nas.toString())));

        assertTrue(Files.exists(old));
    }

    /** A storage directory that does not exist yet is skipped, not a crash mid-sweep. */
    @Test
    void toleratesAStorageDirectoryThatDoesNotExist(@TempDir Path output) throws Exception {
        Path missing = output.resolve("not-created-yet");

        new BackupRunner(output, 30, NO_OP).cleanUp(List.of(project("klippy", missing.toString())));

        assertFalse(Files.exists(missing));
    }

    @Test
    void removesOurEmptiedProjectDirectoryWhereverItIs(@TempDir Path output, @TempDir Path nas)
            throws Exception {
        Path projectDir = Files.createDirectories(nas.resolve("klippy"));

        new BackupRunner(output, 30, NO_OP)
                .cleanUpEmptyDirs(List.of(project("klippy", nas.toString())));

        assertFalse(Files.exists(projectDir));
        assertTrue(Files.exists(nas), "the operator's directory itself is never a candidate");
    }

    /** An empty directory the operator happens to keep beside ours stays. */
    @Test
    void leavesOtherEmptyDirectoriesAlone(@TempDir Path output, @TempDir Path nas) throws Exception {
        Path theirs = Files.createDirectories(nas.resolve("scratch"));

        new BackupRunner(output, 30, NO_OP)
                .cleanUpEmptyDirs(List.of(project("klippy", nas.toString())));

        assertTrue(Files.exists(theirs));
    }

    private static ProjectConfig project(String name, String storageDir) {
        return new ProjectConfig(name, "postgresql://localhost/" + name, "directory", storageDir);
    }

    private static Path aged(Path file, int daysOld) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "dump");
        Files.setLastModifiedTime(
                file, FileTime.from(Instant.now().minus(daysOld, ChronoUnit.DAYS)));
        return file;
    }
}
