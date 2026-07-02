package dev.clippy.bucket.proxy.storage.azure;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.ListBlobsOptions;
import dev.clippy.bucket.proxy.ProxyProperties;
import dev.clippy.bucket.proxy.storage.BucketStorage;
import dev.clippy.bucket.proxy.storage.ObjectKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
@ConditionalOnProperty(name = "proxy.storage.provider", havingValue = "azure")
public class AzureBlobStorageAdapter implements BucketStorage {
    private final BlobContainerClient container;
    private final ProxyProperties.Azure properties;

    public AzureBlobStorageAdapter(ProxyProperties proxyProperties) {
        this.properties = proxyProperties.azure();
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder();
        if (properties.connectionString() != null && !properties.connectionString().isBlank()) {
            builder.connectionString(properties.connectionString());
        } else {
            String endpoint = properties.endpoint();
            if (endpoint == null || endpoint.isBlank()) {
                endpoint = "https://" + properties.accountName() + ".blob.core.windows.net";
            }
            builder.endpoint(endpoint).credential(new DefaultAzureCredentialBuilder().build());
        }
        this.container = builder.buildClient().getBlobContainerClient(properties.containerName());
    }

    @Override
    public UploadResult upload(Path file, String contentType, String folder, String fileName) throws IOException {
        String key = ObjectKeys.objectKey(folder, fileName);
        var blob = container.getBlobClient(key);
        blob.uploadFromFile(file.toString(), true);
        blob.setHttpHeaders(new BlobHttpHeaders().setContentType(
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType));
        return new UploadResult(key, blob.getProperties().getETag());
    }

    @Override
    public List<StoredObject> list(String folder) {
        String prefix = ObjectKeys.normalizeFolder(folder) + "/";
        return container.listBlobs(new ListBlobsOptions().setPrefix(prefix), Duration.ofSeconds(30))
                .stream()
                .map(item -> new StoredObject(
                        item.getName(),
                        item.getProperties().getContentLength(),
                        item.getProperties().getLastModified().toInstant()))
                .toList();
    }

    @Override
    public ObjectMetadata metadata(String key) {
        var blob = container.getBlobClient(ObjectKeys.normalizeKey(key));
        if (!blob.exists()) {
            return new ObjectMetadata(false, null, null, null);
        }
        var metadata = blob.getProperties();
        return new ObjectMetadata(
                true,
                metadata.getBlobSize(),
                metadata.getETag(),
                metadata.getLastModified().toInstant());
    }

    @Override
    public void delete(String key) {
        container.getBlobClient(ObjectKeys.normalizeKey(key)).deleteIfExists();
    }

    @Override
    public String provider() {
        return "azure";
    }

    @Override
    public String containerName() {
        return properties.containerName();
    }

    @Override
    public String location() {
        return properties.accountName();
    }
}
