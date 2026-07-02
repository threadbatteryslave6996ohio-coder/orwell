package dev.clippy.clients.core;

import dev.clippy.auth.client.AuthClientException;
import dev.clippy.clients.filelocker.OfflineFileLockerClient;
import dev.clippy.utils.ClipboardLimits;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

public final class DesktopClipboardMonitor {
    private final ClipboardReader clipboardReader;
    private final ClipboardApiClient apiClient;
    private final String authServerUrl;
    private final String clientId;
    private final OfflineFileLockerClient fileLocker;
    private final Path offlineLogPath;
    private final DesktopClipboardPolicy policy;
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
            DesktopClipboardPolicy policy
    ) {
        this.clipboardReader = Objects.requireNonNull(clipboardReader, "clipboardReader");
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.authServerUrl = authServerUrl;
        this.clientId = Objects.requireNonNull(clientId, "clientId");
        this.fileLocker = Objects.requireNonNull(fileLocker, "fileLocker");
        this.offlineLogPath = Objects.requireNonNull(offlineLogPath, "offlineLogPath");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void poll() {
        if (policy.flushPendingBeforeRead() && pendingOfflineEntry != null && !appendPendingOffline()) {
            return;
        }

        String content;
        try {
            content = clipboardReader.readText();
            if (previousReadFailed && policy.logReadRecovery()) {
                System.out.printf("INFO clientId=%s endpoint=%s authServer=%s Clipboard read recovered.%n",
                        clientId, apiClient.endpoint(), displayAuthServer());
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
            System.err.printf("Skipping oversized clipboard change. chars=%d maxChars=%d%n",
                    content.length(), ClipboardLimits.MAX_CONTENT_CHARACTERS);
            return;
        }

        ClipboardEntry entry = new ClipboardEntry(clientId, content, Instant.now());
        try {
            int statusCode = apiClient.create(entry).statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                lastSentContent = content;
                previousSendFailed = false;
                previousAuthFailed = false;
                System.out.printf("Sent clipboard change. chars=%d%n", content.length());
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
        } catch (AuthClientException exception) {
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
            String backend = policy.includeBackendInReadErrors()
                    ? " backend=" + clipboardReader.name()
                    : "";
            System.err.printf("Clipboard read failed. clientId=%s%s error=%s%n", clientId, backend, message);
            previousReadFailed = true;
        }
    }

    private void logSendFailure(String message) {
        if (!previousSendFailed) {
            System.err.printf("Clipboard send failed. clientId=%s endpoint=%s error=%s%n",
                    clientId, apiClient.endpoint(), message);
            previousSendFailed = true;
        }
    }

    private void logAuthFailure(String message) {
        if (!previousAuthFailed) {
            System.err.printf("Auth refresh failed. clientId=%s authServer=%s error=%s%n",
                    clientId, displayAuthServer(), message);
            previousAuthFailed = true;
        }
    }

    private void logOffline(ClipboardEntry entry, String message) {
        String failure = message == null || message.isBlank() ? "unknown error" : message;
        System.err.printf("Cannot reach remote server. clientId=%s endpoint=%s error=%s%n",
                clientId, apiClient.endpoint(), failure);
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
            System.err.printf("Logged clipboard message to %s. clientId=%s chars=%d%n",
                    offlineLogPath.toAbsolutePath(), clientId, entry.content().length());
            return true;
        } catch (IOException exception) {
            System.err.printf("Clipboard send failed and local JSON log failed. clientId=%s error=%s%n",
                    clientId, exception.getMessage());
            return false;
        }
    }

    private String displayAuthServer() {
        return authServerUrl == null ? "unset" : authServerUrl;
    }
}
