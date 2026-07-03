package dev.clippy.bucket.proxy;

import jakarta.servlet.MultipartConfigElement;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MultipartConfigurationTest {
    @Test
    void appliesConfiguredS3FileSizeToServletMultipartLimits() {
        long configuredLimit = 5L * 1024 * 1024 * 1024;
        ProxyProperties properties = new ProxyProperties(
                new ProxyProperties.Storage("aws", configuredLimit),
                new ProxyProperties.S3("bucket", "us-east-1", null, false, "AES256"),
                new ProxyProperties.Azure("account", "container", null, null),
                new ProxyProperties.AuthServer("http://localhost:8081", "provisioning-key"),
                new ProxyProperties.Management("admin", "password", "session-secret"),
                new ProxyProperties.Cors(List.of()),
                new ProxyProperties.Server("http://localhost"),
                new ProxyProperties.Logging("audit.log")
        );

        MultipartConfigElement config = new MultipartConfiguration().multipartConfigElement(properties);

        assertThat(config.getMaxFileSize()).isEqualTo(configuredLimit);
        assertThat(config.getMaxRequestSize()).isEqualTo(configuredLimit);
    }
}
