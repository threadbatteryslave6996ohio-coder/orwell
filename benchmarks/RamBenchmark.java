import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Measures a Linux server process's RSS with at most five idle client connections. */
public final class RamBenchmark {
    private static final int DEFAULT_CLIENTS = 5;
    private static final long DEFAULT_SETTLE_MILLIS = 2_000;

    private RamBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 5) {
            System.err.println(
                    "Usage: java benchmarks/RamBenchmark.java <server-pid> <host> <port> "
                            + "[clients<=5] [settle-ms]");
            System.exit(2);
        }

        long pid = Long.parseLong(args[0]);
        String host = args[1];
        int port = Integer.parseInt(args[2]);
        int clients = args.length > 3 ? Integer.parseInt(args[3]) : DEFAULT_CLIENTS;
        long settleMillis = args.length > 4 ? Long.parseLong(args[4]) : DEFAULT_SETTLE_MILLIS;
        if (pid < 1 || port < 1 || port > 65_535) {
            throw new IllegalArgumentException("pid and port must be valid positive values");
        }
        if (clients < 1 || clients > 5) {
            throw new IllegalArgumentException("clients must be between 1 and 5");
        }
        if (settleMillis < 0) {
            throw new IllegalArgumentException("settle-ms must not be negative");
        }

        Path statusFile = Path.of("/proc", Long.toString(pid), "status");
        waitForServer(host, port);
        Thread.sleep(settleMillis);
        long idleRss = readRssKib(statusFile);

        List<Socket> sockets = new ArrayList<>(clients);
        try {
            for (int index = 0; index < clients; index++) {
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 2_000);
                sockets.add(socket);
            }
            Thread.sleep(settleMillis);
            long connectedRss = readRssKib(statusFile);
            System.out.printf(
                    "pid=%d clients=%d idle_rss_kib=%d connected_rss_kib=%d delta_kib=%d%n",
                    pid, clients, idleRss, connectedRss, connectedRss - idleRss);
        } finally {
            closeAll(sockets);
        }

        Thread.sleep(settleMillis);
        System.out.printf("after_close_rss_kib=%d%n", readRssKib(statusFile));
    }

    private static void waitForServer(String host, int port) throws Exception {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 50; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), 200);
                return;
            } catch (IOException exception) {
                lastFailure = exception;
                Thread.sleep(100);
            }
        }
        throw new IOException("Server did not accept connections", lastFailure);
    }

    private static long readRssKib(Path statusFile) throws IOException {
        try (var lines = Files.lines(statusFile)) {
            String value = lines
                    .filter(line -> line.startsWith("VmRSS:"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("VmRSS missing from " + statusFile));
            String[] fields = value.trim().split("\\s+");
            return Long.parseLong(fields[1]);
        }
    }

    private static void closeAll(List<Socket> sockets) {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // All remaining sockets still need to be closed.
            }
        }
    }
}
