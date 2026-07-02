package dev.clippy.bucket.proxy.storage.aws;

import dev.clippy.bucket.proxy.ProxyProperties;
import dev.clippy.bucket.proxy.storage.BucketStorage;
import dev.clippy.bucket.proxy.storage.ObjectKeys;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;

@Component
@ConditionalOnProperty(name = "proxy.storage.provider", havingValue = "aws", matchIfMissing = true)
public class AwsS3StorageAdapter implements BucketStorage {
    private final S3Client s3;
    private final ProxyProperties.S3 properties;

    public AwsS3StorageAdapter(ProxyProperties proxyProperties) {
        this.properties = proxyProperties.s3();
        var builder = S3Client.builder().region(Region.of(properties.region()));
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder = builder.endpointOverride(URI.create(properties.endpoint()));
            if (properties.pathStyleAccess()) {
                builder = builder.forcePathStyle(true);
            }
        }
        this.s3 = builder.build();
    }

    @Override
    public UploadResult upload(Path file, String contentType, String folder, String fileName) throws IOException {
        String key = ObjectKeys.objectKey(folder, fileName);
        var requestBuilder = PutObjectRequest.builder()
                .bucket(properties.bucketName())
                .key(key)
                .contentType(contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        if (properties.serverSideEncryption() != null && !properties.serverSideEncryption().isBlank()) {
            requestBuilder.serverSideEncryption(properties.serverSideEncryption());
        }
        var response = s3.putObject(requestBuilder.build(), RequestBody.fromFile(file));
        return new UploadResult(key, response.eTag());
    }

    @Override
    public List<StoredObject> list(String folder) {
        String prefix = ObjectKeys.normalizeFolder(folder) + "/";
        return s3.listObjectsV2Paginator(ListObjectsV2Request.builder()
                        .bucket(properties.bucketName())
                        .prefix(prefix)
                        .build())
                .stream()
                .flatMap(response -> response.contents().stream())
                .map(item -> new StoredObject(item.key(), item.size(), item.lastModified()))
                .toList();
    }

    @Override
    public ObjectMetadata metadata(String key) {
        try {
            var response = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucketName())
                    .key(ObjectKeys.normalizeKey(key))
                    .build());
            return new ObjectMetadata(true, response.contentLength(), response.eTag(), response.lastModified());
        } catch (S3Exception exception) {
            if (exception.statusCode() == 404) {
                return new ObjectMetadata(false, null, null, null);
            }
            throw exception;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucketName())
                .key(ObjectKeys.normalizeKey(key))
                .build());
    }

    @Override
    public String provider() {
        return "aws";
    }

    @Override
    public String containerName() {
        return properties.bucketName();
    }

    @Override
    public String location() {
        return properties.region();
    }
}
