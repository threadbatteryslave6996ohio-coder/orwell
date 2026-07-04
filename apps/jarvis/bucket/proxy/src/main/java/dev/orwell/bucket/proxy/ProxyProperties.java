package dev.orwell.bucket.proxy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "proxy")
public record ProxyProperties(
        Storage storage,
        S3 s3,
        Azure azure,
        AuthServer authServer,
        Management management,
        Cors cors,
        Server server,
        Logging logging
) {
    public record Storage(String provider, long maxFileSize) {}

    public record S3(String bucketName, String region, String endpoint, boolean pathStyleAccess, String serverSideEncryption) {}

    public record Azure(String accountName, String containerName, String endpoint, String connectionString) {}

    public record AuthServer(String baseUrl, String identityProvisioningKey) {}

    public record Management(String username, String password, String sessionSecret) {}

    public record Cors(List<String> allowedOrigins) {}

    public record Server(String url) {}

    public record Logging(String auditFile) {}
}
