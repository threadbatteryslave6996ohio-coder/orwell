package dev.clippy.clients.linux;

import com.fasterxml.jackson.core.JsonProcessingException;
import dev.clippy.clients.core.ClipboardApiClient;
import dev.clippy.clients.core.ClipboardJson;
import dev.clippy.clients.core.ExceptionMessages;
import dev.clippy.clients.filelocker.OfflineFileLockerClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

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

    AuthAuditLogger(OfflineFileLockerClient fileLocker, Path offlineLogPath, String clientId, String authServerUrl) {
        this.fileLocker = fileLocker;
        this.offlineLogPath = offlineLogPath;
        this.clientId = clientId;
        this.authServerUrl = authServerUrl;
    }

    void record(String operation, String status, String message) {
        try {
            fileLocker.append(offlineLogPath, toJson(operation, status, message));
        } catch (IOException exception) {
            System.err.printf("Auth operation log failed. clientId=%s authServer=%s error=%s%n",
                    clientId, displayAuthServer(), exception.getMessage());
        }
    }

    ClipboardApiClient.AuthRefreshListener refreshListener() {
        return new ClipboardApiClient.AuthRefreshListener() {
            @Override
            public void beforeRefresh() {
                System.err.printf("Remote server rejected the bearer token with HTTP 401. Refreshing from auth server. clientId=%s%n",
                        clientId);
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
