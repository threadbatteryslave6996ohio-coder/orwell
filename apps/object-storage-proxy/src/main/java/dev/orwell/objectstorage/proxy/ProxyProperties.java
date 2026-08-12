package dev.orwell.objectstorage.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "object-storage")
public record ProxyProperties(
        Storage storage,
        S3 s3,
        Azure azure,
        AuthServer authServer,
        AdminAuth adminAuth,
        Cors cors,
        Server server,
        Logging logging
) {
    public record Storage(String provider, long maxFileSize) {}

    public record S3(String bucketName, String region, String endpoint, boolean pathStyleAccess) {}

    public record Azure(String accountName, String containerName, String endpoint, String connectionString) {}

    public record AuthServer(String baseUrl, String identityProvisioningKey) {}

    /** The auth deployment holding admin identities. See {@link AdminAuthClient} for why it is a second one. */
    public record AdminAuth(String baseUrl) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Server(String url) {}

    public record Logging(String auditFile) {}
}
