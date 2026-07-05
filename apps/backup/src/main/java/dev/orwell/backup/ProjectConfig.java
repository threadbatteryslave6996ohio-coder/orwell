package dev.orwell.backup;

public record ProjectConfig(
        String name,
        String databaseUrl,
        String storageType,
        String storageDir) {
}
