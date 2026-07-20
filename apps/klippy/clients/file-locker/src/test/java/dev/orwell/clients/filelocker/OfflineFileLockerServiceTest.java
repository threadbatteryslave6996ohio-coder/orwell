package dev.orwell.clients.filelocker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfflineFileLockerServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void serializesConcurrentProcessRequestsWithoutLosingEntries() throws Exception {
        Path socket = tempDir.resolve("locker.sock");
        Path log = tempDir.resolve("offline.json");
        OfflineFileLockerService service = new OfflineFileLockerService(socket, entry -> {
        });
        Thread serviceThread = Thread.ofPlatform().start(() -> {
            try {
                service.run();
            } catch (java.nio.channels.AsynchronousCloseException ignored) {
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });

        try {
            waitForSocket(socket);
            OfflineFileLockerClient client = new OfflineFileLockerClient(socket);
            int entryCount = 40;
            client.append(log, "{\"value\":0}");
            try (ExecutorService writers = Executors.newFixedThreadPool(8)) {
                List<Future<Void>> writes = new ArrayList<>();
                for (int index = 1; index < entryCount; index++) {
                    int value = index;
                    writes.add(writers.submit(() -> {
                        client.append(log, "{\"value\":" + value + "}");
                        return null;
                    }));
                }
                writers.shutdown();
                do {
                    String snapshot = client.read(log);
                    assertTrue(snapshot.startsWith("[\n"));
                    assertTrue(snapshot.endsWith("]\n"));
                } while (!writers.awaitTermination(1, TimeUnit.MILLISECONDS));
                for (Future<Void> write : writes) {
                    write.get();
                }
            }

            String content = client.read(log);
            assertTrue(content.startsWith("[\n"));
            assertTrue(content.endsWith("]\n"));
            assertEquals(entryCount, content.split("\\{\\\"value\\\":", -1).length - 1);

            client.append(log, "{\"value\":40}");
            client.append(log, "{\"value\":40}");
            assertFalse(client.clearIfUnchanged(log, content));
            String updatedContent = client.read(log);
            assertEquals(entryCount + 1, updatedContent.split("\\{\\\"value\\\":", -1).length - 1);
            assertTrue(client.clearIfUnchanged(log, updatedContent));
            assertEquals("[]\n", client.read(log));
        } finally {
            service.close();
            serviceThread.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void timesOutWhenFileLockerStopsResponding() throws Exception {
        Path socket = tempDir.resolve("unresponsive.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            Thread unresponsiveServer = Thread.ofVirtual().start(() -> {
                try (SocketChannel ignored = server.accept()) {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (Exception ignored) {
                }
            });

            OfflineFileLockerClient client = new OfflineFileLockerClient(socket, Duration.ofMillis(100));
            java.io.IOException exception = assertThrows(java.io.IOException.class, client::ping);

            assertTrue(exception.getMessage().contains("timed out"));
            unresponsiveServer.interrupt();
            unresponsiveServer.join(Duration.ofSeconds(1));
        }
    }

    private static void waitForSocket(Path socket) throws Exception {
        Instant deadline = Instant.now().plusSeconds(5);
        while (!Files.exists(socket)) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("File-locker socket was not created.");
            }
            Thread.sleep(10);
        }
    }
}
