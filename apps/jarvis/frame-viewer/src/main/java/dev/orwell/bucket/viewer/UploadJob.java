package dev.orwell.bucket.viewer;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One video backfill, and how far it has got.
 *
 * <p><strong>Why there is a job at all.</strong> A ten-minute video sampled at one frame a second is
 * 600 frames, each of which is a request to the hub — minutes of work. Doing that inside the upload
 * request would mean a connection held open for the duration, a client that cannot tell progress from
 * a hang, and a browser or proxy timeout deciding when it stops. So the upload is accepted (202), the
 * work happens behind it, and this is what the caller polls.
 *
 * <p>Written by the single worker thread and read by any number of request threads, which is why the
 * counters are atomic and the rest is volatile. Nothing here needs a lock: the worker is the only
 * writer, and a reader seeing a slightly stale frame count is showing a progress bar.
 */
final class UploadJob {
    /** Where a backfill has got to. Terminal states are {@link #DONE} and {@link #FAILED}. */
    enum Status {
        /** Uploaded and queued. Waiting for the worker, which runs one job at a time. */
        QUEUED,
        /** ffmpeg is cutting the video into frames. */
        EXTRACTING,
        /** Frames are being pushed to the hub, oldest first. */
        PUSHING,
        DONE,
        FAILED
    }

    private final String id;
    private final String source;
    private final Instant from;
    private final Instant to;
    private final double framesPerSecond;
    private final long videoBytes;
    private final Instant acceptedAt;

    private final AtomicLong framesPushed = new AtomicLong();
    private final AtomicLong framesFailed = new AtomicLong();
    private volatile Status status = Status.QUEUED;
    private volatile int framesTotal;
    private volatile String error;
    private volatile Instant finishedAt;
    private volatile Long firstFrameId;
    private volatile Long lastFrameId;

    UploadJob(String id, String source, Instant from, Instant to, double framesPerSecond,
            long videoBytes, Instant acceptedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.source = Objects.requireNonNull(source, "source");
        this.from = Objects.requireNonNull(from, "from");
        this.to = Objects.requireNonNull(to, "to");
        this.framesPerSecond = framesPerSecond;
        this.videoBytes = videoBytes;
        this.acceptedAt = acceptedAt;
    }

    String id() {
        return id;
    }

    String source() {
        return source;
    }

    Instant from() {
        return from;
    }

    Instant to() {
        return to;
    }

    double framesPerSecond() {
        return framesPerSecond;
    }

    Status status() {
        return status;
    }

    void extracting() {
        this.status = Status.EXTRACTING;
    }

    void pushing(int frames) {
        this.framesTotal = frames;
        this.status = Status.PUSHING;
    }

    /** Records one frame the hub accepted, and the id it gave it. */
    void pushed(long frameId) {
        framesPushed.incrementAndGet();
        if (firstFrameId == null) {
            firstFrameId = frameId;
        }
        lastFrameId = frameId;
    }

    /**
     * Records one frame the hub refused.
     *
     * <p>Counted rather than fatal: a single frame the hub rejected costs one frame of a backfill, and
     * abandoning the other 599 to report it would be the wrong trade. The count is on the job so it
     * cannot pass unnoticed.
     */
    void failedFrame() {
        framesFailed.incrementAndGet();
    }

    void done() {
        this.status = Status.DONE;
        this.finishedAt = Instant.now();
    }

    void failed(String message) {
        this.error = message;
        this.status = Status.FAILED;
        this.finishedAt = Instant.now();
    }

    /**
     * When the frame at {@code index} of {@code total} is dated.
     *
     * <p>The video is stretched linearly onto the window: frame 0 lands on {@code from}, and the
     * spacing is the window divided by the frame count. That deliberately ignores the video's own
     * duration and frame rate — the caller said this footage covers 10:00 to 10:05, and that is the
     * answer, whether the file itself is four minutes or six.
     *
     * <p>The last frame lands just <em>inside</em> {@code to} rather than on it, because the repo's
     * time windows are half-open: dividing by {@code total} rather than {@code total - 1} means a
     * {@code GET /frames?from=…&to=…} with the same two bounds returns exactly the frames this upload
     * created — no boundary frame missing, none from the next window swept in.
     */
    Instant timestampFor(int index, int total) {
        if (total <= 1) {
            return from;
        }
        Duration window = Duration.between(from, to);
        // Nanos rather than seconds: a 5-second window over 100 frames is 50 ms a frame, and integer
        // seconds would put every one of them at `from`.
        long step = window.toNanos() / total;
        return from.plusNanos(step * index);
    }

    Map<String, Object> asJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("uploadId", id);
        json.put("status", status.name().toLowerCase());
        json.put("source", source);
        json.put("from", from.toString());
        json.put("to", to.toString());
        json.put("fps", framesPerSecond);
        json.put("videoBytes", videoBytes);
        json.put("framesTotal", framesTotal);
        json.put("framesPushed", framesPushed.get());
        json.put("framesFailed", framesFailed.get());
        // The id range the backfill occupies, so a caller can go straight to what it created.
        json.put("firstFrameId", firstFrameId);
        json.put("lastFrameId", lastFrameId);
        json.put("acceptedAt", acceptedAt.toString());
        json.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
        json.put("error", error);
        return json;
    }
}
