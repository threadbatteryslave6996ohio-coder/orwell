package dev.orwell.clients.core;

import dev.orwell.clients.core.env.ClientAuthSession;
import dev.orwell.clients.filelocker.OfflineFileLockerClient;
import dev.orwell.clients.filelocker.OfflineFileLockerService;
import dev.orwell.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopClipboardMonitorTest {
    private static final Logger NO_OP_LOGGER = entry -> {
    };

    @TempDir
    Path tempDir;

    @Test
    void linuxModeFlushesPendingEntryBeforeReadingClipboardAgain() throws Exception {
        Path socket = tempDir.resolve("locker.sock");
        Path offlineLog = tempDir.resolve("offline.json");
        OfflineFileLockerClient fileLocker = new OfflineFileLockerClient(socket);
        AtomicInteger reads = new AtomicInteger();
        ClipboardReader reader = () -> reads.getAndIncrement() == 0 ? "pending text" : null;
        DesktopClipboardMonitor monitor = monitor(reader, fileLocker, offlineLog);

        monitor.poll();
        try (RunningFileLocker ignored = startFileLocker(socket)) {
            monitor.poll();
            String content = fileLocker.read(offlineLog);
            assertEquals("pending text", ClipboardJson.mapper().readTree(content).get(0).get("content").textValue());
            assertEquals(2, reads.get());
        }
    }

    @Test
    void linuxModeFlushesPendingEntryEvenWhenNextClipboardReadFails() throws Exception {
        Path socket = tempDir.resolve("locker.sock");
        Path offlineLog = tempDir.resolve("offline.json");
        OfflineFileLockerClient fileLocker = new OfflineFileLockerClient(socket);
        AtomicInteger reads = new AtomicInteger();
        ClipboardReader reader = () -> {
            if (reads.getAndIncrement() == 0) {
                return "pending text";
            }
            throw new IOException("clipboard helper failed");
        };
        DesktopClipboardMonitor monitor = monitor(reader, fileLocker, offlineLog);

        monitor.poll();
        try (RunningFileLocker ignored = startFileLocker(socket)) {
            monitor.poll();
            String content = fileLocker.read(offlineLog);
            assertEquals("pending text", ClipboardJson.mapper().readTree(content).get(0).get("content").textValue());
        }
    }

    private static DesktopClipboardMonitor monitor(
            ClipboardReader reader, OfflineFileLockerClient fileLocker, Path offlineLog) {
        ClientAuthSession auth = new ClientAuthSession(null, "client-a", null, "token-a");
        ClipboardApiClient apiClient = new ClipboardApiClient(
                URI.create("http://127.0.0.1:1/clipboard"), auth, Duration.ofMillis(100));
        return new DesktopClipboardMonitor(
                reader, apiClient, null, "client-a", fileLocker,
                offlineLog, new LinuxClipboardPolicy(), NO_OP_LOGGER);
    }

    private static RunningFileLocker startFileLocker(Path socket) throws Exception {
        OfflineFileLockerService service = new OfflineFileLockerService(socket, NO_OP_LOGGER);
        Thread thread = Thread.ofPlatform().start(() -> {
            try {
                service.run();
            } catch (java.nio.channels.AsynchronousCloseException ignored) {
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
        Instant deadline = Instant.now().plusSeconds(5);
        while (!java.nio.file.Files.exists(socket)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("File-locker socket was not created.");
            }
            Thread.sleep(10);
        }
        return new RunningFileLocker(service, thread);
    }

    private record RunningFileLocker(OfflineFileLockerService service, Thread thread) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            service.close();
            thread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }
}
