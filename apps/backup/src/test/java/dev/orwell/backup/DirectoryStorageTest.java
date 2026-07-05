package dev.orwell.backup;

import dev.orwell.backup.storage.DirectoryStorage;
import dev.orwell.backup.storage.StorageBackup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DirectoryStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void storesDumpInProjectSubdirectory() throws Exception {
        StorageBackup storage = new DirectoryStorage(tempDir);
        Path dump = tempDir.resolve("test_20260705_120000.dump");
        Files.writeString(dump, "dump content");

        Path stored = storage.store("myproject", dump);

        assertTrue(Files.exists(stored));
        assertEquals(tempDir.resolve("myproject/test_20260705_120000.dump"), stored);
        assertFalse(Files.exists(dump));
    }

    @Test
    void sanitizesProjectName() throws Exception {
        StorageBackup storage = new DirectoryStorage(tempDir);
        Path dump = tempDir.resolve("backup.dump");
        Files.writeString(dump, "data");

        Path stored = storage.store("my project/../evil", dump);

        assertTrue(Files.exists(stored));
        assertTrue(stored.startsWith(tempDir.resolve("my_project____evil")));
    }
}
