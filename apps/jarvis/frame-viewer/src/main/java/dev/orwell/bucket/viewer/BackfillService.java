package dev.orwell.bucket.viewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.orwell.http.LoggingHttpClient;
import dev.orwell.logging.Logger;
import dev.orwell.primitives.Sha256;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns an uploaded video into stored frames, dated across the window the caller named.
 *
 * <p><strong>It pushes to the hub; it does not write to the table.</strong> That is the repo's rule
 * and not a detail: the hub is the only thing that ingests frames, so anything else that wants frames
 * to exist asks it to make them. Writing rows here would allocate ids outside the hub's sequence, skip
 * the live fan-out — {@code jarvis-person-detection} would never see a backfilled frame — and make
 * this the second writer to a table the viewer otherwise opens read-only. Going through
 * {@code POST /frames} means a backfilled frame is indistinguishable from a live one, which is the
 * whole point of backfilling it.
 *
 * <p><strong>One job at a time, frames pushed in order, and paced.</strong> Three constraints that
 * each have a reason:
 * <ul>
 *   <li><em>In order</em>, because the hub allocates a frame's id on arrival. Pushing oldest-first
 *       keeps id order the same as capture order, which is what the viewer's back button and the
 *       stream's {@code Last-Event-ID} resume both walk.
 *   <li><em>One at a time</em>, because two concurrent backfills would interleave their ids and
 *       neither would occupy a contiguous range — and because two ffmpeg processes on a container
 *       sized for a page server is how this app gets killed for memory.
 *   <li><em>Paced</em>, because the hub stores frames behind its live stream through a bounded queue
 *       ({@code HUB_STORE_QUEUE_DEPTH}, 512 by default). Pushing 600 frames as fast as a loop can
 *       manage overruns that queue, and an overrun frame is delivered live and never stored — which
 *       for a backfill means it does not exist. Pacing keeps the writer ahead of us. If a backfill
 *       still reports {@code framesFailed} or the hub's {@code framesUnstoredTotal} climbs, the hub's
 *       {@code HUB_STORE_MODE=sync} makes storage part of the response instead.
 * </ul>
 */
final class BackfillService {
    /** Finished jobs kept for polling before the oldest is forgotten. */
    private static final int JOB_HISTORY = 50;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, UploadJob> jobs = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private final ExecutorService worker;
    private final VideoFrames videos;
    private final HttpClient http;
    private final String hubUrl;
    private final double defaultFramesPerSecond;
    private final int pushesPerSecond;
    private final Logger logger;

    BackfillService(VideoFrames videos, String hubUrl, double defaultFramesPerSecond,
            int pushesPerSecond, Logger logger) {
        this.videos = Objects.requireNonNull(videos, "videos");
        this.hubUrl = hubUrl == null ? "" : trimTrailingSlash(hubUrl.trim());
        this.defaultFramesPerSecond = defaultFramesPerSecond;
        this.pushesPerSecond = Math.max(1, pushesPerSecond);
        this.logger = Objects.requireNonNull(logger, "logger");
        // One thread, named, and a daemon: a backfill in flight must not keep a stopped process
        // alive, and the job it was running reports as failed on the next start because it is gone.
        this.worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "frame-backfill");
            thread.setDaemon(true);
            return thread;
        });
        this.http = LoggingHttpClient.wrap(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), logger);
    }

    /** False when no hub is configured, which is what makes the upload route answer 503. */
    boolean configured() {
        return !hubUrl.isBlank();
    }

    String hubUrl() {
        return hubUrl;
    }

    double defaultFramesPerSecond() {
        return defaultFramesPerSecond;
    }

    /**
     * Queues an uploaded video for backfill and returns immediately.
     *
     * @param video the uploaded file, already on disk. This service owns it from here and deletes it
     *              when the job ends, however it ends.
     */
    UploadJob accept(Path video, String source, Instant from, Instant to, Double framesPerSecond,
            long videoBytes) {
        double fps = framesPerSecond == null ? defaultFramesPerSecond : framesPerSecond;
        // Zero-padded so that lexical order is arrival order — recentJobs() and forgetOldJobs()
        // both sort on this string. Unpadded, "10-…" sorts before "9-…", so past nine uploads the
        // newest job was listed mid-page and, past JOB_HISTORY, evicted while much older ones
        // survived and its poll started 404ing. Same trick as VideoFrames' frame-%06d.jpg.
        String id = "%012d-%s".formatted(
                sequence.incrementAndGet(), UUID.randomUUID().toString().substring(0, 8));
        UploadJob job = new UploadJob(id, source, from, to, fps, videoBytes, Instant.now());
        jobs.put(id, job);
        forgetOldJobs();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("uploadId", id);
        metadata.put("source", source);
        metadata.put("from", from.toString());
        metadata.put("to", to.toString());
        metadata.put("fps", fps);
        metadata.put("videoBytes", videoBytes);
        logger.info("Accepted a video for backfill.", metadata);

        worker.execute(() -> run(job, video));
        return job;
    }

    Optional<UploadJob> job(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    /** Every job still remembered, newest first. */
    List<UploadJob> recentJobs() {
        List<UploadJob> recent = new ArrayList<>(jobs.values());
        recent.sort(Comparator.comparing(UploadJob::id).reversed());
        return recent;
    }

    // -----------------------------------------------------------------------
    // The worker
    // -----------------------------------------------------------------------

    private void run(UploadJob job, Path video) {
        Path scratch = null;
        try {
            scratch = Files.createTempDirectory("frame-backfill-");
            job.extracting();
            List<Path> frames = videos.extract(video, scratch, job.framesPerSecond());
            job.pushing(frames.size());
            logger.info("Cut an uploaded video into frames.", Map.of(
                    "uploadId", job.id(), "frames", frames.size(), "source", job.source()));

            push(job, frames);

            job.done();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("uploadId", job.id());
            metadata.put("source", job.source());
            metadata.put("frames", frames.size());
            logger.info("Backfilled a video into the frame store.", metadata);
        } catch (VideoFrames.ExtractionException exception) {
            job.failed(exception.getMessage());
            logger.warn("Could not cut an uploaded video into frames.", Map.of(
                    "uploadId", job.id(), "error", String.valueOf(exception.getMessage())));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.failed("the backfill was interrupted");
        } catch (Exception exception) {
            job.failed(String.valueOf(exception.getMessage()));
            logger.error("A video backfill failed.", Map.of(
                    "uploadId", job.id(), "error", exception.toString()));
        } finally {
            // The upload and every frame cut out of it, gone — whether the job worked or not. The
            // frames live in Postgres now, and a scratch directory nobody deletes is how a viewer
            // with a 512 MB container fills its disk instead.
            deleteQuietly(video);
            deleteRecursivelyQuietly(scratch);
        }
    }

    private void push(UploadJob job, List<Path> frames) throws InterruptedException {
        long minimumGapNanos = Duration.ofSeconds(1).toNanos() / pushesPerSecond;
        for (int index = 0; index < frames.size(); index++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException();
            }
            long startedAt = System.nanoTime();
            Instant capturedAt = job.timestampFor(index, frames.size());
            try {
                byte[] bytes = Files.readAllBytes(frames.get(index));
                job.pushed(pushOne(job, bytes, capturedAt, index));
            } catch (IOException | RuntimeException exception) {
                // One frame the hub would not take costs one frame. InterruptedException is not
                // caught here on purpose — a stopping process should abandon the backfill, not log
                // 600 warnings on the way out.
                job.failedFrame();
                logger.warn("A backfilled frame was not stored.", Map.of(
                        "uploadId", job.id(),
                        "frameIndex", index,
                        "error", exception.toString()));
            }
            // Pace from the start of the push, so a slow hub is not also delayed by this.
            long remaining = minimumGapNanos - (System.nanoTime() - startedAt);
            if (remaining > 0) {
                Thread.sleep(Duration.ofNanos(remaining));
            }
        }
    }

    /**
     * Pushes one frame and returns the id the hub gave it.
     *
     * <p>The hash goes with it. The hub verifies {@code frameSha256} against the bytes and answers
     * 400 if they disagree, so a frame corrupted between ffmpeg and here is refused rather than
     * stored — and this is the one place in a backfill where that could happen silently.
     */
    private long pushOne(UploadJob job, byte[] frame, Instant capturedAt, int index)
            throws IOException, InterruptedException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", job.source());
        payload.put("frameBase64", Base64.getEncoder().encodeToString(frame));
        payload.put("frameSha256", Sha256.hex(frame));
        payload.put("capturedAt", capturedAt.toString());
        // The producer's own counter, which for a backfill is the frame's position in the video.
        payload.put("frameIndex", index);

        HttpRequest request = HttpRequest.newBuilder(URI.create(hubUrl + "/frames"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)))
                .build();
        HttpResponse<String> response =
                http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("the hub answered " + response.statusCode()
                    + " for frame " + index);
        }
        JsonNode body = json.readTree(response.body());
        return body.path("frameId").asLong(-1);
    }

    // -----------------------------------------------------------------------
    // Housekeeping
    // -----------------------------------------------------------------------

    /**
     * Drops the oldest finished jobs once there are more than {@link #JOB_HISTORY}.
     *
     * <p>Unfinished jobs are never dropped, however many there are: the map is what a caller polls
     * for progress, and forgetting a job that is still running would look to it like the upload was
     * lost. The worker runs one at a time, so the queue cannot grow faster than uploads arrive.
     */
    private void forgetOldJobs() {
        if (jobs.size() <= JOB_HISTORY) {
            return;
        }
        List<UploadJob> finished = jobs.values().stream()
                .filter(job -> job.status() == UploadJob.Status.DONE
                        || job.status() == UploadJob.Status.FAILED)
                .sorted(Comparator.comparing(UploadJob::id))
                .toList();
        int excess = jobs.size() - JOB_HISTORY;
        for (int index = 0; index < excess && index < finished.size(); index++) {
            jobs.remove(finished.get(index).id());
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            logger.warn("Could not delete an uploaded video.",
                    Map.of("path", path.toString(), "error", exception.toString()));
        }
    }

    private void deleteRecursivelyQuietly(Path directory) {
        if (directory == null) {
            return;
        }
        try (var entries = Files.list(directory)) {
            entries.forEach(this::deleteQuietly);
        } catch (IOException exception) {
            logger.warn("Could not clear a backfill scratch directory.",
                    Map.of("path", directory.toString(), "error", exception.toString()));
        }
        deleteQuietly(directory);
    }

    private static String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
