package dev.orwell.backup.storage;

import java.io.IOException;
import java.nio.file.Path;

public interface StorageBackup {
    Path store(String projectName, Path dumpFile) throws IOException;
}
