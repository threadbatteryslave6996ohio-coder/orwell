package dev.orwell.clients.filelocker;

import dev.orwell.env.EnvFiles;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class OfflineFileLockerService implements AutoCloseable {
    private final Path socketPath;
    private final Map<Path, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();
    private final ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor();
    private ServerSocketChannel server;
    private boolean ownsSocket;

    public OfflineFileLockerService(Path socketPath) {
        this.socketPath = socketPath.toAbsolutePath().normalize();
    }

    public static void main(String[] args) throws Exception {
        Path socketPath = args.length == 0
                ? configuredSocketPath()
                : Path.of(args[0]);
        try (OfflineFileLockerService service = new OfflineFileLockerService(socketPath)) {
            Runtime.getRuntime().addShutdownHook(new Thread(service::close));
            System.out.printf("Clippy file-locker service listening at %s%n", socketPath.toAbsolutePath());
            service.run();
        }
    }

    private static Path configuredSocketPath() throws IOException {
        String configured = EnvFiles.load().get("OFFLINE_FILE_LOCKER_SOCKET");
        return configured == null || configured.isBlank()
                ? OfflineFileLockerClient.DEFAULT_SOCKET_PATH
                : Path.of(configured);
    }

    public void run() throws IOException {
        prepareSocketPath();
        server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socketPath));
        ownsSocket = true;
        setOwnerOnlyPermissions(socketPath);

        while (server.isOpen()) {
            try {
                SocketChannel client = server.accept();
                requests.submit(() -> handle(client));
            } catch (java.nio.channels.AsynchronousCloseException exception) {
                break;
            }
        }
    }

    private void handle(SocketChannel channel) {
        try (channel) {
            DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
            try {
                int operation = input.readUnsignedByte();
                Path path = Path.of(FileLockerProtocol.readString(input)).toAbsolutePath().normalize();
                String response = switch (operation) {
                    case FileLockerProtocol.PING -> "";
                    case FileLockerProtocol.READ -> readLocked(path);
                    case FileLockerProtocol.APPEND -> {
                        appendLocked(path, FileLockerProtocol.readString(input));
                        yield "";
                    }
                    case FileLockerProtocol.CLEAR_IF_UNCHANGED -> Boolean.toString(
                            clearIfUnchangedLocked(path, FileLockerProtocol.readString(input)));
                    default -> throw new IOException("Unknown file-locker operation: " + operation);
                };
                output.writeByte(FileLockerProtocol.OK);
                FileLockerProtocol.writeString(output, response);
            } catch (Exception exception) {
                output.writeByte(FileLockerProtocol.ERROR);
                FileLockerProtocol.writeString(output, failureMessage(exception));
            }
            output.flush();
        } catch (IOException exception) {
            System.err.printf("File-locker IPC request failed: %s%n", failureMessage(exception));
        }
    }

    private String readLocked(Path path) throws IOException {
        ReentrantReadWriteLock.ReadLock lock = lockFor(path).readLock();
        lock.lock();
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } finally {
            lock.unlock();
        }
    }

    private void appendLocked(Path path, String jsonEntry) throws IOException {
        ReentrantReadWriteLock.WriteLock lock = lockFor(path).writeLock();
        lock.lock();
        try {
            String updated;
            if (!Files.exists(path) || Files.size(path) == 0) {
                updated = "[\n  " + jsonEntry + "\n]\n";
            } else {
                String existing = Files.readString(path, StandardCharsets.UTF_8);
                if (containsExactEntry(existing, jsonEntry)) {
                    return;
                }
                int arrayEnd = existing.lastIndexOf(']');
                if (arrayEnd < 0) {
                    throw new IOException("Offline JSON log is not a JSON array: " + path);
                }
                String beforeEnd = existing.substring(0, arrayEnd).stripTrailing();
                String separator = beforeEnd.endsWith("[") ? "\n" : ",\n";
                updated = beforeEnd + separator + "  " + jsonEntry + "\n]\n";
            }
            atomicWrite(path, updated);
        } finally {
            lock.unlock();
        }
    }

    private static boolean containsExactEntry(String array, String jsonEntry) {
        String framedEntry = "\n  " + jsonEntry;
        return array.contains(framedEntry + ",\n") || array.contains(framedEntry + "\n]");
    }

    private boolean clearIfUnchangedLocked(Path path, String expectedContent) throws IOException {
        ReentrantReadWriteLock.WriteLock lock = lockFor(path).writeLock();
        lock.lock();
        try {
            String currentContent = Files.readString(path, StandardCharsets.UTF_8);
            if (!currentContent.equals(expectedContent)) {
                return false;
            }
            atomicWrite(path, "[]\n");
            return true;
        } finally {
            lock.unlock();
        }
    }

    private ReentrantReadWriteLock lockFor(Path path) {
        return locks.computeIfAbsent(path, ignored -> new ReentrantReadWriteLock(true));
    }

    private static void atomicWrite(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Offline log path must have a parent directory: " + path);
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".clippy-offline-", ".tmp");
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            setOwnerOnlyPermissions(temporary);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void prepareSocketPath() throws IOException {
        Path parent = socketPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(socketPath)) {
            return;
        }
        try (SocketChannel existing = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            existing.connect(UnixDomainSocketAddress.of(socketPath));
            throw new IOException("A file-locker service is already listening at " + socketPath);
        } catch (java.net.ConnectException exception) {
            Files.delete(socketPath);
        }
    }

    private static void setOwnerOnlyPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // Unix-domain sockets require a Unix-like platform; non-POSIX filesystems are uncommon but valid.
        }
    }

    private static String failureMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
        }
        requests.shutdownNow();
        if (ownsSocket) {
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException exception) {
                System.err.printf("Could not remove file-locker socket %s: %s%n", socketPath, exception.getMessage());
            }
        }
    }
}
