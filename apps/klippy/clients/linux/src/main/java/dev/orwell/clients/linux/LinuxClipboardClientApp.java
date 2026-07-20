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
import dev.orwell.clients.core.PollInterval;
import dev.orwell.clients.core.env.ClientAuthSession;
import dev.orwell.clients.core.env.ClientEnvs;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.clients.linux.clipboard.LinuxClipboardReader;
import dev.orwell.clients.linux.clipboard.LinuxClipboardReaderFactory;
import dev.orwell.env.Env;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;

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
            AuthAuditLogger auditLogger,
            Logger logger
    ) {
        ClipboardApiClient apiClient = new ClipboardApiClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                endpoint,
                authSession,
                Duration.ofSeconds(10),
                auditLogger.refreshListener());
        this.monitor = new DesktopClipboardMonitor(
                clipboardReader, apiClient, authServerUrl, clientId, fileLocker,
                OFFLINE_LOG_PATH, new LinuxClipboardPolicy(), logger);
    }

    public static void main(String[] args) throws IOException {
        Logger logger = new ConsoleLogger("klippy-linux-client");
        Env env = ClientEnvs.load();
        ClientConfig config = ClientConfig.load(env, ClientIdentity.hostnameOrRandom("linux-"));
        long pollIntervalMs = PollInterval.resolve(env);
        LinuxClipboardReader clipboardReader = LinuxClipboardReaderFactory.create(env);
        OfflineFileLockerClient fileLocker = OfflineFileLockerFactory.create(env);
        fileLocker.ping();

        AuthAuditLogger auditLogger =
                new AuthAuditLogger(fileLocker, OFFLINE_LOG_PATH, config.clientId(), config.authServerUrl(), logger);
        initializeAuth(config, auditLogger, logger);

        LinuxClipboardClientApp app = new LinuxClipboardClientApp(
                clipboardReader, config.endpoint(), config.authServerUrl(), config.clientId(),
                config.authSession(), fileLocker, auditLogger, logger);
        new DesktopClientRunner(app.monitor, pollIntervalMs, logger)
                .start("Klippy Linux client started.", config, Map.of("clipboardBackend", clipboardReader.name()));
    }

    private static void initializeAuth(ClientConfig config, AuthAuditLogger auditLogger, Logger logger) {
        if (!config.authSession().canRefresh() || config.authServerUrl() == null) {
            ClientAuthInitializer.initialize(config.authSession(), config, logger);
            return;
        }

        auditLogger.record("refresh", "started", "startup token refresh");
        try {
            ClientAuthInitializer.initialize(config.authSession(), config, logger);
            auditLogger.record("refresh", "succeeded", "startup token refresh");
        } catch (RuntimeException exception) {
            auditLogger.record("refresh", "failed", ExceptionMessages.messageWithCause(exception));
            throw exception;
        }
    }
}
