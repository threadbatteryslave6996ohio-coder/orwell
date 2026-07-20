package dev.orwell.bucket.proxy.streaming;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import dev.orwell.bucket.proxy.JarvisProxyEnvs;
import dev.orwell.env.EnvOption;
import dev.orwell.env.http.EnvLoader;
import dev.orwell.logging.ConsoleLogger;
import dev.orwell.logging.Logger;
import dev.orwell.primitives.Sha256;

public final class AnalysisWorker {
    private static final byte[] SOI = {(byte) 0xFF, (byte) 0xD8};
    private static final byte[] EOI = {(byte) 0xFF, (byte) 0xD9};
    /** Bound in-progress frame assembly so a stream that never emits an EOI can't exhaust memory. */
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    /** Log at most one send-failure line per this many consecutive failures. */
    private static final long SEND_FAILURE_LOG_INTERVAL = 100;

    private AnalysisWorker() {
    }

    public static void main(String[] args) throws Exception {
        // Standalone main with no Spring context, so the sink is built here and passed down
        // rather than reached for statically. ConsoleLogger routes WARN/ERROR to stderr, and its
        // name supplies the prefix these lines used to hand-write.
        Logger logger = new ConsoleLogger("stream-worker");
        String endpoint = loadEndpoint();
        if (endpoint.isBlank()) {
            drainJpegFrames(System.in, logger);
            return;
        }
        run(endpoint, System.in, logger);
    }

    /**
     * Reads only the stream endpoint from the environment. The worker deliberately does not
     * validate the full {@link JarvisProxyEnvs} schema: a malformed value for an unrelated
     * proxy variable (storage size, S3 flags, ...) must not crash the frame pipeline.
     */
    private static String loadEndpoint() {
        Map<String, String> source = EnvLoader.load("file");
        EnvOption<String> option = JarvisProxyEnvs.STREAM_ANALYSIS_ENDPOINT;
        String value = source.get(option.name());
        if (value == null || value.isBlank()) {
            return option.defaultValue().orElse("");
        }
        return value;
    }

    private static void run(String endpoint, InputStream input, Logger logger) throws Exception {
        HttpClient client = HttpClient.newBuilder().build();
        int frameIndex = 0;
        long consecutiveFailures = 0;
        for (byte[] frame : extractFrames(input, logger)) {
            if (frame.length == 0) {
                continue;
            }
            frameIndex++;
            Instant now = Instant.now();
            double timestamp = now.getEpochSecond() + (now.getNano() / 1_000_000_000.0);
            String payload = toJson(timestamp, frameIndex, Sha256.hex(frame), Base64.getEncoder().encodeToString(frame));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            try {
                client.send(request, HttpResponse.BodyHandlers.ofString());
                if (consecutiveFailures > 0) {
                    logger.info("Endpoint reachable again.", Map.of("failedSends", consecutiveFailures));
                    consecutiveFailures = 0;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (IOException exception) {
                consecutiveFailures++;
                // Rate-limit so a persistently down endpoint doesn't flood the log, but is never silent.
                if (consecutiveFailures == 1 || consecutiveFailures % SEND_FAILURE_LOG_INTERVAL == 0) {
                    logger.error("Failed to POST frame.", Map.of(
                            "endpoint", endpoint,
                            "consecutiveFailures", consecutiveFailures,
                            "error", exception.toString()
                    ));
                }
            }
        }
    }

    private static void drainJpegFrames(InputStream input, Logger logger) throws IOException {
        for (byte[] ignored : extractFrames(input, logger)) {
            // no-op
        }
    }

    static Iterable<byte[]> extractFrames(InputStream input, Logger logger) {
        return () -> new java.util.Iterator<>() {
            private final byte[] readBuffer = new byte[4096];
            // Unconsumed bytes live in buf[0, size); the whole stream is scanned once overall
            // because we never re-materialize or re-scan already-examined bytes.
            private byte[] buf = new byte[8192];
            private int size;
            private int frameStart = -1; // index of the current SOI in buf, or -1 while searching
            private int scanPos;         // next index to examine
            private byte[] next;
            private boolean finished;

            @Override
            public boolean hasNext() {
                if (next != null) {
                    return true;
                }
                if (finished) {
                    return false;
                }
                try {
                    while (next == null && !finished) {
                        next = pollFrame();
                        if (next != null) {
                            break;
                        }

                        int read = input.read(readBuffer);
                        if (read == -1) {
                            finished = true;
                            break;
                        }
                        append(readBuffer, read);
                    }
                    return next != null;
                } catch (IOException exception) {
                    finished = true;
                    return false;
                }
            }

            @Override
            public byte[] next() {
                if (!hasNext()) {
                    throw new java.util.NoSuchElementException();
                }
                byte[] value = next;
                next = null;
                return value;
            }

            private byte[] pollFrame() {
                if (frameStart < 0) {
                    int soi = indexOf(buf, size, SOI, scanPos);
                    if (soi < 0) {
                        // No frame start yet: drop everything but a possible trailing 0xFF
                        // (a marker that may be split across the next read).
                        int keepFrom = size > 0 && buf[size - 1] == (byte) 0xFF ? size - 1 : size;
                        compact(keepFrom);
                        scanPos = 0;
                        return null;
                    }
                    frameStart = soi;
                    scanPos = soi + 2;
                }

                if (size - frameStart > MAX_FRAME_BYTES) {
                    logger.warn("Dropping in-progress frame with no end marker.",
                            Map.of("maxFrameBytes", MAX_FRAME_BYTES));
                    compact(size);
                    frameStart = -1;
                    scanPos = 0;
                    return null;
                }

                int end = indexOf(buf, size, EOI, scanPos);
                if (end < 0) {
                    // Resume just before the end so an EOI split across reads is not missed.
                    scanPos = Math.max(frameStart + 2, size - 1);
                    return null;
                }

                byte[] frame = Arrays.copyOfRange(buf, frameStart, end + 2);
                compact(end + 2);
                frameStart = -1;
                scanPos = 0;
                return frame;
            }

            private void append(byte[] src, int length) {
                ensureCapacity(size + length);
                System.arraycopy(src, 0, buf, size, length);
                size += length;
            }

            private void ensureCapacity(int needed) {
                if (needed <= buf.length) {
                    return;
                }
                int newCapacity = buf.length;
                while (newCapacity < needed) {
                    newCapacity <<= 1;
                }
                buf = Arrays.copyOf(buf, newCapacity);
            }

            /** Drop buf[0, from), shifting the retained tail to the front. */
            private void compact(int from) {
                if (from <= 0) {
                    return;
                }
                int remaining = size - from;
                if (remaining > 0) {
                    System.arraycopy(buf, from, buf, 0, remaining);
                }
                size = remaining;
            }
        };
    }

    private static int indexOf(byte[] data, int length, byte[] needle, int fromIndex) {
        outer: for (int i = Math.max(fromIndex, 0); i <= length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String toJson(double timestamp, int frameIndex, String sha256, String frameBase64) {
        return "{\"timestamp\":" + timestamp
                + ",\"frameIndex\":" + frameIndex
                + ",\"frameSha256\":\"" + escapeJson(sha256)
                + "\",\"frameBase64\":\"" + escapeJson(frameBase64) + "\"}";
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
