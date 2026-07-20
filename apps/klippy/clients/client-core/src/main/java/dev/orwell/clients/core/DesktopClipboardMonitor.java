package dev.orwell.clients.core;

import dev.orwell.auth.http.client.HttpAuthenticationException;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.logging.Logger;
import dev.orwell.utils.ClipboardLimits;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class DesktopClipboardMonitor {
    private final ClipboardReader clipboardReader;
    private final ClipboardApiClient apiClient;
    private final String authServerUrl;
    private final String clientId;
    private final OfflineFileLockerClient fileLocker;
    private final Path offlineLogPath;
    private final DesktopClipboardPolicy policy;
    private final Logger logger;
    private String lastSentContent;
    private boolean previousReadFailed;
    private boolean previousSendFailed;
    private boolean previousAuthFailed;
    private ClipboardEntry pendingOfflineEntry;

    public DesktopClipboardMonitor(
            ClipboardReader clipboardReader,
            ClipboardApiClient apiClient,
            String authServerUrl,
            String clientId,
            OfflineFileLockerClient fileLocker,
            Path offlineLogPath,
            DesktopClipboardPolicy policy,
            Logger logger
    ) {
        this.clipboardReader = Objects.requireNonNull(clipboardReader, "clipboardReader");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.authServerUrl = authServerUrl;
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.fileLocker = Objects.requireNonNull(fileLocker, "fileLocker");
        this.offlineLogPath = Objects.requireNonNull(offlineLogPath, "offlineLogPath");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void poll() {
        if (policy.flushPendingBeforeRead() && pendingOfflineEntry != null && !appendPendingOffline()) {
            return;
        }

        String content;
        try {
            content = clipboardReader.readText();
            if (previousReadFailed && policy.logReadRecovery()) {
                logger.info("Clipboard read recovered.", Map.of(
                        "clientId", clientId,
                        "endpoint", String.valueOf(apiClient.endpoint()),
                        "authServer", displayAuthServer()));
            }
            previousReadFailed = false;
        } catch (Exception exception) {
            logReadFailure(ExceptionMessages.message(exception));
            return;
        }

        if (!policy.flushPendingBeforeRead() && pendingOfflineEntry != null) {
            if (!appendPendingOffline()) {
                return;
            }
            if (Objects.equals(content, lastSentContent)) {
                return;
            }
        }

        if (content == null || policy.ignoreEmptyContent() && content.isEmpty()
                || Objects.equals(content, lastSentContent)) {
            return;
        }
        if (!ClipboardLimits.isWithinContentLimit(content)) {
            lastSentContent = content;
            logger.warn("Skipping oversized clipboard change.", Map.of(
                    "chars", content.length(),
                    "maxChars", ClipboardLimits.MAX_CONTENT_CHARACTERS));
            return;
        }

        ClipboardEntry entry = new ClipboardEntry(clientId, content, Instant.now());
        try {
            int statusCode = apiClient.create(entry).statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                lastSentContent = content;
                previousSendFailed = false;
                previousAuthFailed = false;
                logger.info("Sent clipboard change.", Map.of("chars", content.length()));
            } else if (statusCode == 401) {
                logAuthFailure("Remote server rejected the bearer token with HTTP 401.");
                logOffline(entry, "Unauthorized");
            } else {
                String failure = "Server responded with HTTP " + statusCode;
                logSendFailure(failure);
                logOffline(entry, failure);
            }
        } catch (IOException exception) {
            logSendFailure(ExceptionMessages.message(exception));
            logOffline(entry, ExceptionMessages.message(exception));
        } catch (HttpAuthenticationException exception) {
            logAuthFailure(ExceptionMessages.messageWithCause(exception));
            logOffline(entry, ExceptionMessages.messageWithCause(exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            logSendFailure("Interrupted while sending clipboard content.");
            logOffline(entry, "Interrupted while sending clipboard content.");
        }
    }

    private void logReadFailure(String message) {
        if (!previousReadFailed) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("clientId", clientId);
            if (policy.includeBackendInReadErrors()) {
                metadata.put("backend", clipboardReader.name());
            }
            metadata.put("error", message);
            logger.error("Clipboard read failed.", metadata);
            previousReadFailed = true;
        }
    }

    private void logSendFailure(String message) {
        if (!previousSendFailed) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("clientId", clientId);
            metadata.put("endpoint", String.valueOf(apiClient.endpoint()));
            metadata.put("error", message);
            logger.error("Clipboard send failed.", metadata);
            previousSendFailed = true;
        }
    }

    private void logAuthFailure(String message) {
        if (!previousAuthFailed) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("clientId", clientId);
            metadata.put("authServer", displayAuthServer());
            metadata.put("error", message);
            logger.error("Auth refresh failed.", metadata);
            previousAuthFailed = true;
        }
    }

    private void logOffline(ClipboardEntry entry, String message) {
        String failure = message == null || message.isBlank() ? "unknown error" : message;
        // Recoverable: the entry is queued to the offline log and replayed by the sync client.
        logger.warn("Cannot reach remote server.", Map.of(
                "clientId", clientId,
                "endpoint", String.valueOf(apiClient.endpoint()),
                "error", failure));
        pendingOfflineEntry = entry;
        appendPendingOffline();
    }

    private boolean appendPendingOffline() {
        ClipboardEntry entry = pendingOfflineEntry;
        if (entry == null) {
            return true;
        }
        try {
            fileLocker.append(offlineLogPath, ClipboardJson.write(entry));
            lastSentContent = entry.content();
            pendingOfflineEntry = null;
            logger.warn("Logged clipboard message offline.", Map.of(
                    "offlineLog", String.valueOf(offlineLogPath.toAbsolutePath()),
                    "clientId", clientId,
                    "chars", entry.content().length()));
            return true;
        } catch (IOException exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("clientId", clientId);
            metadata.put("error", exception.getMessage());
            logger.error("Clipboard send failed and local JSON log failed.", metadata);
            return false;
        }
    }

    private String displayAuthServer() {
        return authServerUrl == null ? "unset" : authServerUrl;
    }
}
