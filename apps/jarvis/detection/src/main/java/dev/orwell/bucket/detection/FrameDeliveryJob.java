package dev.orwell.bucket.detection;

import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.entity.FrameSubscriptionEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.bucket.detection.repository.FrameSubscriptionRepository;
import dev.orwell.logging.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The bastion's fan-out half: pushes stored frames to every active subscriber, tracking a cursor
 * per subscription.
 *
 * <p>Each active subscription carries a {@code lastDeliveredId}; a round fetches the frames with a
 * greater id — restricted to its {@code source} when it has one — oldest first, and posts them in
 * order. The cursor advances only after a 2xx, so a subscriber that was down catches up on a later
 * round instead of silently missing frames.
 *
 * <p>Delivery is therefore <em>at least once</em>: a subscriber that processes a frame and then
 * fails to return 2xx will be sent it again. Key on {@code frameId}, which is stable.
 *
 * <p>A failing subscription blocks only itself. Delivery stops at the first failure for that one
 * subscription — otherwise a subscriber would see later frames before the one it rejected — and
 * every other subscription continues in the same round.
 *
 * <p>Catch-up is bounded by retention, not by the cursor: {@link FrameRetentionJob} deletes aged
 * frames whether or not they were delivered, so a subscriber down longer than
 * {@code DETECTION_FRAME_RETENTION_SECONDS} resumes from the oldest surviving frame and the ones
 * in between are gone. That is the deliberate trade for a table holding video bytes.
 */
@Component
public class FrameDeliveryJob {
    /**
     * How many frames one subscription may catch up on per round. Bounded so a subscriber that has
     * been down cannot monopolise a round, and so one HTTP failure is never more than this many
     * wasted attempts. Lower than gmail's equivalent because a frame is orders of magnitude
     * larger than a mail payload and the whole batch is held in memory.
     */
    private static final int BATCH = 20;

    private final FrameSubscriptionRepository subscriptions;
    private final FrameEventRepository events;
    private final FrameSender sender;
    private final Logger logger;
    // Rounds must not overlap: two rounds walking the same cursor would double-deliver.
    private final AtomicBoolean delivering = new AtomicBoolean(false);
    private final AtomicLong framesDeliveredTotal = new AtomicLong();

    public FrameDeliveryJob(
            FrameSubscriptionRepository subscriptions,
            FrameEventRepository events,
            FrameSender sender,
            Logger logger) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.events = Objects.requireNonNull(events, "events");
        this.sender = Objects.requireNonNull(sender, "sender");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public long framesDeliveredTotal() {
        return framesDeliveredTotal.get();
    }

    @Scheduled(fixedRateString = "${detection.fanout.interval-seconds}", timeUnit = TimeUnit.SECONDS)
    void scheduledDelivery() {
        if (!delivering.compareAndSet(false, true)) {
            return;
        }
        try {
            deliverPending();
        } catch (Exception exception) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("error", exception.getMessage());
            logger.warn("Frame delivery round failed; will retry next interval.", metadata);
        } finally {
            delivering.set(false);
        }
    }

    /** One pass over every active subscription. Package-visible so tests can drive a round. */
    void deliverPending() {
        for (FrameSubscriptionEntity subscription : subscriptions.findByActiveTrueOrderByIdAsc()) {
            try {
                deliverFor(subscription);
            } catch (Exception exception) {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("subscriptionId", subscription.getId());
                metadata.put("client", subscription.getUrl());
                metadata.put("error", exception.getMessage());
                logger.error("Frame delivery failed for a subscription; continuing with the rest.",
                        metadata);
            }
        }
    }

    private void deliverFor(FrameSubscriptionEntity subscription) {
        List<FrameEventEntity> pending = pendingFor(subscription);
        for (FrameEventEntity frame : pending) {
            if (!sender.send(subscription.getUrl(), frame)) {
                // Leave the cursor where it is: this frame is retried next round, and the frames
                // after it stay behind it so the subscriber never sees them out of order.
                return;
            }
            subscription.advanceTo(frame.getId());
            subscriptions.save(subscription);
            framesDeliveredTotal.incrementAndGet();
        }
    }

    private List<FrameEventEntity> pendingFor(FrameSubscriptionEntity subscription) {
        PageRequest page = PageRequest.of(0, BATCH);
        long cursor = subscription.getLastDeliveredId();
        return subscription.getSource() == null
                ? events.findByIdGreaterThanOrderByIdAsc(cursor, page)
                : events.findByIdGreaterThanAndSourceOrderByIdAsc(cursor, subscription.getSource(), page);
    }
}
