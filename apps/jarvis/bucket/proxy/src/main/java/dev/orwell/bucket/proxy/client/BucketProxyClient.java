package dev.orwell.bucket.proxy.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.PathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public final class BucketProxyClient {
    private static final ParameterizedTypeReference<ListResponse> LIST_RESPONSE_TYPE = new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public BucketProxyClient(String baseUrl) {
        this(RestClient.builder()
                .baseUrl(baseUrl)
                .messageConverters(converters -> converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules())))
                .build());
    }

    public BucketProxyClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public HealthResponse health() {
        return get("/health", HealthResponse.class, "health check");
    }

    public LoginResponse login(String username, String password) {
        return postJson("/login", new LoginRequest(username, password), LoginResponse.class, "login");
    }

    public UploadResponse upload(String clientId, String bearerToken, Path file, String folder, String fileName) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new PathResource(file));
        if (StringUtils.hasText(folder)) {
            builder.part("folder", folder);
        }
        if (StringUtils.hasText(fileName)) {
            builder.part("fileName", fileName);
        }
        return postMultipart("/upload", clientId, bearerToken, builder.build(), UploadResponse.class, "upload");
    }

    public BatchUploadResponse batchUpload(String clientId, String bearerToken, List<Path> files, String folder) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        for (Path file : files) {
            builder.part("files", new PathResource(file));
        }
        if (StringUtils.hasText(folder)) {
            builder.part("folder", folder);
        }
        return postMultipart("/batch-upload", clientId, bearerToken, builder.build(), BatchUploadResponse.class, "batch upload");
    }

    public ListResponse list(String clientId, String bearerToken, String folder) {
        String path = "/list/" + UriUtils.encodePathSegment(folder, StandardCharsets.UTF_8);
        return getAuthenticated(path, clientId, bearerToken, LIST_RESPONSE_TYPE, "list");
    }

    public MetadataResponse metadata(String clientId, String bearerToken, String key) {
        String path = "/metadata/" + UriUtils.encodePath(key, StandardCharsets.UTF_8);
        return getAuthenticated(path, clientId, bearerToken, MetadataResponse.class, "metadata fetch");
    }

    public DeleteResponse delete(String clientId, String bearerToken, String key) {
        String path = "/delete/" + UriUtils.encodePath(key, StandardCharsets.UTF_8);
        return deleteAuthenticated(path, clientId, bearerToken, DeleteResponse.class, "delete");
    }

    private <T> T get(String path, Class<T> responseType, String operation) {
        try {
            return restClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BucketProxyClientException("Proxy rejected " + operation + " with HTTP " + response.getStatusCode().value(),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (BucketProxyClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BucketProxyClientException("Cannot complete " + operation + " with proxy.", exception);
        }
    }

    private <T> T postJson(String path, Object body, Class<T> responseType, String operation) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BucketProxyClientException("Proxy rejected " + operation + " with HTTP " + response.getStatusCode().value(),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (BucketProxyClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BucketProxyClientException("Cannot complete " + operation + " with proxy.", exception);
        }
    }

    private <T> T postMultipart(String path,
                                String clientId,
                                String bearerToken,
                                MultiValueMap<String, HttpEntity<?>> body,
                                Class<T> responseType,
                                String operation) {
        try {
            return restClient.post()
                    .uri(path)
                    .headers(headers -> authenticate(headers, clientId, bearerToken))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BucketProxyClientException("Proxy rejected " + operation + " with HTTP " + response.getStatusCode().value(),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (BucketProxyClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BucketProxyClientException("Cannot complete " + operation + " with proxy.", exception);
        }
    }

    private <T> T getAuthenticated(String path,
                                   String clientId,
                                   String bearerToken,
                                   Class<T> responseType,
                                   String operation) {
        try {
            return restClient.get()
                    .uri(path)
                    .headers(headers -> authenticate(headers, clientId, bearerToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BucketProxyClientException("Proxy rejected " + operation + " with HTTP " + response.getStatusCode().value(),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (BucketProxyClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BucketProxyClientException("Cannot complete " + operation + " with proxy.", exception);
        }
    }

    private <T> T getAuthenticated(String path,
                                   String clientId,
                                   String bearerToken,
                                   ParameterizedTypeReference<T> responseType,
                                   String operation) {
        try {
            return restClient.get()
                    .uri(path)
                    .headers(headers -> authenticate(headers, clientId, bearerToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BucketProxyClientException("Proxy rejected " + operation + " with HTTP " + response.getStatusCode().value(),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (BucketProxyClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BucketProxyClientException("Cannot complete " + operation + " with proxy.", exception);
        }
    }

    private <T> T deleteAuthenticated(String path,
                                      String clientId,
                                      String bearerToken,
                                      Class<T> responseType,
                                      String operation) {
        try {
            return restClient.delete()
                    .uri(path)
                    .headers(headers -> authenticate(headers, clientId, bearerToken))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (request, response) -> {
                        throw new BucketProxyClientException("Proxy rejected " + operation + " with HTTP " + response.getStatusCode().value(),
                                response.getStatusCode().value());
                    })
                    .body(responseType);
        } catch (BucketProxyClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new BucketProxyClientException("Cannot complete " + operation + " with proxy.", exception);
        }
    }

    private static void authenticate(HttpHeaders headers, String clientId, String bearerToken) {
        headers.setBearerAuth(bearerToken);
        headers.set("X-Client-Id", clientId);
    }

    public record LoginRequest(String username, String password) {}
    public record LoginResponse(boolean success, String clientId, String token, String tokenType, String error) {}
    public record HealthResponse(String status, String bucket, String region, String auth, String authServer) {}
    public record UploadResponse(boolean success, String message, String key, String etag) {}
    public record BatchUploadFile(String key, String etag) {}
    public record BatchUploadResponse(boolean success, String message, List<BatchUploadFile> files) {}
    public record ListFile(String key, long size, Instant lastModified) {}
    public record ListResponse(boolean success, String folder, List<ListFile> files) {}
    public record MetadataResponse(boolean success, boolean exists, String key, Long size, String etag, Instant lastModified) {}
    public record DeleteResponse(boolean success, String message, String key) {}
}
