package dev.orwell.backup;

public record DatabaseConfig(
        String label,
        String host,
        int port,
        String user,
        String password,
        String database) {
}
