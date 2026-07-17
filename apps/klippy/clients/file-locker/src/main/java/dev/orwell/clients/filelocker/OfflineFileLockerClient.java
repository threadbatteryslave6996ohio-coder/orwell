package dev.orwell.clients.filelocker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class OfflineFileLockerClient {
    public static final Path DEFAULT_SOCKET_PATH = Path.of("/tmp/klippy-offline-file-locker.sock");
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final UnixDomainSocketAddress address;
    private final Duration requestTimeout;

    public OfflineFileLockerClient(Path socketPath) {
        this(socketPath, DEFAULT_REQUEST_TIMEOUT);
    }

    OfflineFileLockerClient(Path socketPath, Duration requestTimeout) {
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("File-locker request timeout must be positive.");
        }
        this.address = UnixDomainSocketAddress.of(socketPath.toAbsolutePath().normalize());
        this.requestTimeout = requestTimeout;
    }

    public String read(Path path) throws IOException {
        return request(FileLockerProtocol.READ, path, null);
    }

    public void ping() throws IOException {
        request(FileLockerProtocol.PING, Path.of("."), null);
    }

    public void append(Path path, String jsonEntry) throws IOException {
        request(FileLockerProtocol.APPEND, path, jsonEntry);
    }

    public boolean clearIfUnchanged(Path path, String expectedContent) throws IOException {
        return Boolean.parseBoolean(request(FileLockerProtocol.CLEAR_IF_UNCHANGED, path, expectedContent));
    }

    private String request(int operation, Path path, String content) throws IOException {
        FutureTask<String> request = new FutureTask<>(() -> requestBlocking(operation, path, content));
        Thread.ofVirtual().name("klippy-file-locker-request").start(request);
        try {
            return request.get(requestTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            request.cancel(true);
            throw new IOException("File-locker request timed out after " + requestTimeout.toMillis() + " ms.", exception);
        } catch (InterruptedException exception) {
            request.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the file-locker service.", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IOException("File-locker request failed.", cause);
        }
    }

    private String requestBlocking(int operation, Path path, String content) throws IOException {
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(address);
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(channel));
            output.writeByte(operation);
            FileLockerProtocol.writeString(output, path.toAbsolutePath().normalize().toString());
            if (content != null) {
                FileLockerProtocol.writeString(output, content);
            }
            output.flush();

            DataInputStream input = new DataInputStream(Channels.newInputStream(channel));
            int status = input.readUnsignedByte();
            String response = FileLockerProtocol.readString(input);
            if (status != FileLockerProtocol.OK) {
                throw new IOException("File-locker service rejected the request: " + response);
            }
            return response;
        } catch (java.net.ConnectException exception) {
            throw new IOException("Cannot connect to file-locker service at " + address.getPath()
                    + ". Start scripts/start-file-locker.sh first.", exception);
        }
    }
}
