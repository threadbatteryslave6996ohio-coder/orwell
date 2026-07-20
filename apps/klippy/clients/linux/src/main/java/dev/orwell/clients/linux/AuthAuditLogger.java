package dev.orwell.clients.linux;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.orwell.clients.core.ClipboardApiClient;
import dev.orwell.clients.core.ClipboardJson;
import dev.orwell.clients.core.ExceptionMessages;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.logging.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Linux-only sink for auth-refresh audit records. Appends one JSON line per event to the
 * offline log, serialized through the shared Jackson mapper, and adapts {@link
 * ClipboardApiClient.AuthRefreshListener} events into those records.
 */
final class AuthAuditLogger {
    private final OfflineFileLockerClient fileLocker;
    private final Path offlineLogPath;
    private final String clientId;
    private final String authServerUrl;
    private final Logger logger;

    AuthAuditLogger(
            OfflineFileLockerClient fileLocker,
            Path offlineLogPath,
            String clientId,
            String authServerUrl,
            Logger logger) {
        this.fileLocker = fileLocker;
        this.offlineLogPath = offlineLogPath;
        this.clientId = clientId;
        this.authServerUrl = authServerUrl;
        this.logger = logger;
    }

    void record(String operation, String status, String message) {
        try {
            fileLocker.append(offlineLogPath, toJson(operation, status, message));
        } catch (IOException exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("clientId", clientId);
            metadata.put("authServer", displayAuthServer());
            metadata.put("error", exception.getMessage());
            logger.error("Auth operation log failed.", metadata);
        }
    }

    ClipboardApiClient.AuthRefreshListener refreshListener() {
        return new ClipboardApiClient.AuthRefreshListener() {
            @Override
            public void beforeRefresh() {
                // Recoverable: the client refreshes the token and retries the request.
                logger.warn("Remote server rejected the bearer token with HTTP 401. Refreshing from auth server.",
                        Map.of("clientId", clientId));
                record("refresh", "started", "HTTP 401 token refresh");
            }

            @Override
            public void afterRefresh() {
                record("refresh", "succeeded", "HTTP 401 token refresh");
            }

            @Override
            public void refreshFailed(RuntimeException exception) {
                record("refresh", "failed", ExceptionMessages.messageWithCause(exception));
            }
        };
    }

    String toJson(String operation, String status, String message) {
        try {
            return ClipboardJson.mapper().writeValueAsString(ClipboardJson.mapper().createObjectNode()
                    .put("type", "auth")
                    .put("clientId", clientId)
                    .put("authServer", displayAuthServer())
                    .put("operation", operation)
                    .put("status", status)
                    .put("message", message == null ? "" : message)
                    .put("timestamp", Instant.now().toString()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize auth audit entry.", exception);
        }
    }

    private String displayAuthServer() {
        return authServerUrl == null ? "unset" : authServerUrl;
    }
}
