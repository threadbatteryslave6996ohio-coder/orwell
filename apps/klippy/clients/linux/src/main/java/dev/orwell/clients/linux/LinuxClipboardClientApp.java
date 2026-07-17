package dev.orwell.clients.linux;

import dev.orwell.clients.core.ClipboardApiClient;
import dev.orwell.clients.core.ClientAuthInitializer;
import dev.orwell.clients.core.ClientConfig;
import dev.orwell.clients.core.ClientIdentity;
import dev.orwell.clients.core.DesktopClientRunner;
import dev.orwell.clients.core.DesktopClipboardMonitor;
import dev.orwell.clients.core.ExceptionMessages;
import dev.orwell.clients.core.LinuxClipboardPolicy;
import dev.orwell.clients.core.OfflineFileLockerFactory;
import dev.orwell.clients.core.OfflineLogPath;
import dev.orwell.clients.core.PollIntervalValidator;
import dev.orwell.clients.core.env.ClientAuthSession;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.clients.linux.clipboard.LinuxClipboardReader;
import dev.orwell.clients.linux.clipboard.LinuxClipboardReaderFactory;
import dev.orwell.env.Env;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

public final class LinuxClipboardClientApp {
    private static final Path OFFLINE_LOG_PATH = OfflineLogPath.DEFAULT;

    private final DesktopClipboardMonitor monitor;

    private LinuxClipboardClientApp(
            LinuxClipboardReader clipboardReader,
            URI endpoint,
            String authServerUrl,
            String clientId,
            ClientAuthSession authSession,
            OfflineFileLockerClient fileLocker,
            AuthAuditLogger auditLogger
    ) {
        ClipboardApiClient apiClient = new ClipboardApiClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                endpoint,
                authSession,
                Duration.ofSeconds(10),
                auditLogger.refreshListener());
        this.monitor = new DesktopClipboardMonitor(
                clipboardReader, apiClient, authServerUrl, clientId, fileLocker,
                OFFLINE_LOG_PATH, new LinuxClipboardPolicy());
    }

    public static void main(String[] args) throws IOException {
        OfflineLogPath.migrateLegacyIfPresent();
        Env env = ClientEnvs.load();
        ClientConfig config = ClientConfig.load(env, ClientIdentity.hostnameOrRandom("linux-"));
        long pollIntervalMs = env.has(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS)
                ? PollIntervalValidator.validate(env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS), 100L)
                : 1000L;
        LinuxClipboardReader clipboardReader = LinuxClipboardReaderFactory.create(env);
        OfflineFileLockerClient fileLocker = OfflineFileLockerFactory.create(env);
        fileLocker.ping();

        AuthAuditLogger auditLogger =
                new AuthAuditLogger(fileLocker, OFFLINE_LOG_PATH, config.clientId(), config.authServerUrl());
        initializeAuth(config, auditLogger);

        LinuxClipboardClientApp app = new LinuxClipboardClientApp(
                clipboardReader, config.endpoint(), config.authServerUrl(), config.clientId(),
                config.authSession(), fileLocker, auditLogger);
        new DesktopClientRunner(app.monitor, pollIntervalMs)
                .start("Klippy Linux client started.", config, Map.of("clipboardBackend", clipboardReader.name()));
    }

    private static void initializeAuth(ClientConfig config, AuthAuditLogger auditLogger) {
        if (!config.authSession().canRefresh() || config.authServerUrl() == null) {
            ClientAuthInitializer.initialize(config.authSession(), config);
            return;
        }

        auditLogger.record("refresh", "started", "startup token refresh");
        try {
            ClientAuthInitializer.initialize(config.authSession(), config);
            auditLogger.record("refresh", "succeeded", "startup token refresh");
        } catch (RuntimeException exception) {
            auditLogger.record("refresh", "failed", ExceptionMessages.messageWithCause(exception));
            throw exception;
        }
    }
}
