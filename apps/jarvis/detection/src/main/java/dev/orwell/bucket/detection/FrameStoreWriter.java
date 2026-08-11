package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.logging.Logger;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes broadcast frames to the store, behind the live stream rather than in front of it.
 *
 * <p>The point is that a Postgres stall must not freeze the video. Frames go out to connected
 * clients the moment they arrive; persisting them is what makes a <em>reconnecting</em> client able
 * to catch up, and that can lag by a little without anyone watching noticing.
 *
 * <p>One writer thread drains the queue in batches, so rows land in id order and replay stays
 * contiguous. When the queue is full the submitting thread waits briefly and then gives up on that
 * frame: the alternatives are blocking ingest on the database — the exact coupling this exists to
 * remove — or growing the queue until the heap runs out. A dropped frame was still delivered live;
 * what is lost is the ability to replay it, which shows up as a hole in the id sequence a
 * reconnecting client skips over. {@code framesUnstoredTotal} counts them.
 *
 * <p>{@code DETECTION_STORE_MODE=sync} turns all of this off and writes on the calling thread, so
 * a frame is never broadcast unless it is also stored. That is the durable-but-coupled choice.
 */
@Component
public class FrameStoreWriter {
    /** Rows per transaction. */
    private static final int BATCH = 25;
    /** How long a submitting thread waits for queue space before abandoning a frame. */
    private static final long OFFER_TIMEOUT_MILLIS = 50;

    private final FrameEventRepository events;
    private final Logger logger;
    private final boolean async;
    private final BlockingQueue<FrameEventEntity> queue;
    private final Thread writer;
    private final AtomicLong framesUnstoredTotal = new AtomicLong();
    private volatile boolean running = true;

    public FrameStoreWriter(
            FrameEventRepository events,
            @Value("${detection.store.mode}") String mode,
            @Value("${detection.store.queue-depth}") int queueDepth,
            Logger logger) {
        this.events = Objects.requireNonNull(events, "events");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.async = !"sync".equalsIgnoreCase(mode);
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueDepth));
        this.writer = async
                ? Thread.ofVirtual().name("frame-store-writer").start(this::run)
                : null;
    }

    public long framesUnstoredTotal() {
        return framesUnstoredTotal.get();
    }

    public int pendingWrites() {
        return queue.size();
    }

    /** Queues a frame for storage, or stores it inline in {@code sync} mode. */
    public void submit(FrameEventEntity frame) {
        if (!async) {
            events.save(frame);
            return;
        }
        try {
            if (!queue.offer(frame, OFFER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                unstored(frame, "store queue full");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            unstored(frame, "interrupted");
        }
    }

    private void run() {
        List<FrameEventEntity> batch = new ArrayList<>(BATCH);
        while (running || !queue.isEmpty()) {
            try {
                FrameEventEntity first = queue.poll(200, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, BATCH - 1);
                events.saveAll(batch);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception exception) {
                // The frames in hand are lost to replay, but they were already delivered live and
                // the writer has to survive to keep the next ones storable.
                framesUnstoredTotal.addAndGet(batch.size());
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("frames", batch.size());
                metadata.put("error", exception.getMessage());
                logger.error("Could not store a batch of frames; they stay live-only.", metadata);
            } finally {
                batch.clear();
            }
        }
    }

    private void unstored(FrameEventEntity frame, String reason) {
        framesUnstoredTotal.incrementAndGet();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("frameId", frame.getId());
        metadata.put("source", frame.getSource());
        metadata.put("reason", reason);
        logger.warn("Frame delivered live but not stored; it cannot be replayed.", metadata);
    }

    /** Drains what is queued so a clean shutdown does not silently lose replayable frames. */
    @PreDestroy
    void drainAndStop() {
        running = false;
        Thread pending = writer;
        if (pending == null) {
            return;
        }
        try {
            pending.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
