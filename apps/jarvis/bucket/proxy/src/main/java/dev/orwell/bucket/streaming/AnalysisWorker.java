package dev.orwell.bucket.streaming;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import dev.orwell.env.http.EnvLoader;

public final class AnalysisWorker {
    private AnalysisWorker() {
    }

    public static void main(String[] args) throws Exception {
        var env = StreamingEnvs.from(EnvLoader.load("file"));
        String endpoint = env.get(StreamingEnvs.STREAM_ANALYSIS_ENDPOINT);
        if (endpoint.isBlank()) {
            drainJpegFrames(System.in);
            return;
        }
        run(endpoint, System.in);
    }

    private static void run(String endpoint, InputStream input) throws Exception {
        HttpClient client = HttpClient.newBuilder().build();
        int frameIndex = 0;
        int sentCount = 0;
        for (byte[] frame : extractFrames(input)) {
            if (frame.length == 0) {
                continue;
            }
            frameIndex++;
            double timestamp = Instant.now().getEpochSecond() + (Instant.now().getNano() / 1_000_000_000.0);
            String payload = toJson(timestamp, frameIndex, sha256Hex(frame), Base64.getEncoder().encodeToString(frame));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build();
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    sentCount++;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw exception;
            } catch (IOException ignored) {
                // downstream unavailable; continue draining stream
            }
        }
    }

    private static void drainJpegFrames(InputStream input) throws IOException {
        for (byte[] ignored : extractFrames(input)) {
            // no-op
        }
    }

    static Iterable<byte[]> extractFrames(InputStream input) {
        return () -> new java.util.Iterator<>() {
            private final byte[] buffer = new byte[4096];
            private final ByteArrayOutputStream collector = new ByteArrayOutputStream();
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

                        int read = input.read(buffer);
                        if (read == -1) {
                            finished = true;
                            break;
                        }
                        collector.write(buffer, 0, read);
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
                byte[] data = collector.toByteArray();
                int start = indexOf(data, new byte[]{(byte) 0xFF, (byte) 0xD8}, 0);
                if (start < 0) {
                    int retainedStart = data.length > 0 && data[data.length - 1] == (byte) 0xFF
                            ? data.length - 1
                            : data.length;
                    retain(data, retainedStart);
                    return null;
                }

                int end = indexOf(data, new byte[]{(byte) 0xFF, (byte) 0xD9}, start + 2);
                if (end < 0) {
                    retain(data, start);
                    return null;
                }

                byte[] frame = Arrays.copyOfRange(data, start, end + 2);
                retain(data, end + 2);
                return frame;
            }

            private void retain(byte[] data, int fromIndex) {
                collector.reset();
                collector.write(data, fromIndex, data.length - fromIndex);
            }
        };
    }

    private static int indexOf(byte[] data, byte[] needle, int fromIndex) {
        outer: for (int i = fromIndex; i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format("%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash frame.", exception);
        }
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
