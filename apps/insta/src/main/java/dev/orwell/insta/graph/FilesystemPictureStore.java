package dev.orwell.insta.graph;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Writes pictures under a directory, content-addressed. The local option — useful for running the
 * sync before a bucket exists, and what the tests use.
 */
public final class FilesystemPictureStore implements PictureStore {
    private final Path root;

    public FilesystemPictureStore(Path root) {
        this.root = Objects.requireNonNull(root, "root");
    }

    @Override
    public String put(String contentHash, byte[] bytes) throws Exception {
        String key = PictureKeys.keyFor(contentHash);
        Path target = root.resolve(key);
        // Same hash means the same bytes, so an existing file is already correct.
        if (Files.exists(target)) {
            return key;
        }
        Files.createDirectories(target.getParent());
        // Write beside the target and move, so a crash mid-write cannot leave a truncated image
        // sitting at a key the database will later claim is complete.
        Path partial = Files.createTempFile(target.getParent(), contentHash, ".partial");
        Files.write(partial, bytes);
        Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return key;
    }
}
