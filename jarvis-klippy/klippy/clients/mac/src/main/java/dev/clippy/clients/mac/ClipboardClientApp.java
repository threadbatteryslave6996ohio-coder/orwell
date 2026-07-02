package dev.clippy.clients.mac;

import dev.clippy.clients.core.ClipboardApiClient;
import dev.clippy.clients.core.ClipboardReader;
import dev.clippy.clients.core.ClientAuthInitializer;
import dev.clippy.clients.core.ClientConfig;
import dev.clippy.clients.core.ClientIdentity;
import dev.clippy.clients.core.DesktopClientRunner;
import dev.clippy.clients.core.DesktopClipboardMonitor;
import dev.clippy.clients.core.MacClipboardPolicy;
import dev.clippy.clients.core.OfflineFileLockerFactory;
import dev.clippy.clients.core.PollIntervalValidator;
import dev.clippy.clients.core.env.ClientAuthSession;
import dev.clippy.clients.core.env.ClientEnvs;
import dev.clippy.clients.filelocker.OfflineFileLockerClient;
import dev.clippy.utils.envmanager.Env;

import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

public final class ClipboardClientApp {
    private static final Path OFFLINE_LOG_PATH = Path.of("clippy-offline-clipboard.json");

    private final DesktopClipboardMonitor monitor;

    private ClipboardClientApp(
            Clipboard clipboard,
            URI endpoint,
            String authServerUrl,
            String clientId,
            ClientAuthSession authSession,
            OfflineFileLockerClient fileLocker
    ) {
        ClipboardApiClient apiClient = new ClipboardApiClient(endpoint, authSession, Duration.ofSeconds(10));
        this.monitor = new DesktopClipboardMonitor(
                clipboardReader(clipboard), apiClient, authServerUrl, clientId, fileLocker,
                OFFLINE_LOG_PATH, new MacClipboardPolicy());
    }

    /** Reads the current string content of {@code clipboard}, or {@code null} when it holds no text. */
    static ClipboardReader clipboardReader(Clipboard clipboard) {
        return () -> {
            if (!clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                return null;
            }
            Object data = clipboard.getData(DataFlavor.stringFlavor);
            return data instanceof String text ? text : null;
        };
    }

    public static void main(String[] args) throws IOException {
        Env env = ClientEnvs.load();
        ClientConfig config = ClientConfig.load(env, ClientIdentity.hostnameOrRandom("client-"));
        long pollIntervalMs = env.has(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS)
                ? PollIntervalValidator.validate(env.get(ClientEnvs.CLIPBOARD_POLL_INTERVAL_MS), 1L)
                : 1L;

        Clipboard clipboard;
        try {
            clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        } catch (HeadlessException exception) {
            throw new IllegalStateException("No graphical clipboard is available in this environment.", exception);
        }

        OfflineFileLockerClient fileLocker = OfflineFileLockerFactory.create(env);
        fileLocker.ping();
        ClientAuthInitializer.initialize(config.authSession(), config);

        ClipboardClientApp app = new ClipboardClientApp(
                clipboard, config.endpoint(), config.authServerUrl(), config.clientId(), config.authSession(), fileLocker);
        new DesktopClientRunner(app.monitor, pollIntervalMs).start("Clippy client started.", config);
    }
}
