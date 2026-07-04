package dev.orwell.bucket.proxy.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Provider-neutral object storage operations used by the HTTP proxy. */
public interface BucketStorage {
    UploadResult upload(Path file, String contentType, String folder, String fileName) throws IOException;

    List<StoredObject> list(String folder);

    ObjectMetadata metadata(String key);

    void delete(String key);

    String provider();

    String containerName();

    String location();

    record UploadResult(String key, String etag) {}

    record StoredObject(String key, long size, Instant lastModified) {}

    record ObjectMetadata(boolean exists, Long size, String etag, Instant lastModified) {}
}
