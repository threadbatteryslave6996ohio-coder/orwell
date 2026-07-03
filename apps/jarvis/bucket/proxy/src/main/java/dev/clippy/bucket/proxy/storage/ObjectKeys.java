package dev.clippy.bucket.proxy.storage;

import java.nio.file.Path;

public final class ObjectKeys {
    private ObjectKeys() {}

    public static String objectKey(String folder, String fileName) {
        String safeFolder = normalizeFolder(folder);
        String safeFileName = Path.of(fileName == null || fileName.isBlank() ? "upload.bin" : fileName)
                .getFileName()
                .toString();
        return safeFolder + "/" + safeFileName;
    }

    public static String normalizeFolder(String folder) {
        String value = (folder == null || folder.isBlank() ? "uploads" : folder)
                .trim()
                .replaceAll("^/+|/+$", "");
        value = value.replaceAll("[^A-Za-z0-9._/-]", "_").replaceAll("/+", "/");
        return value.isBlank() ? "uploads" : value;
    }

    public static String normalizeKey(String key) {
        String value = key == null ? "" : key.trim().replaceAll("^/+|/+$", "");
        if (value.isBlank() || value.contains("..")) {
            throw new IllegalArgumentException("Invalid object key.");
        }
        return value;
    }
}
