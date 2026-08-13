package dev.orwell.bucket.hub;

import dev.orwell.bucket.hub.entity.FrameEventEntity;
import dev.orwell.bucket.hub.repository.FrameEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The hub's read half: stored frames captured between two instants, in pages.
 *
 * <p>This is the after-the-fact question the stream cannot answer. {@code GET /frames/stream}
 * exists to follow a feed and resumes by frame id — fine for a watcher that remembers where it got
 * to, useless for "show me the back door between 10:00 and 10:05", because nobody holds the id of a
 * frame they have never seen. So the range is expressed in capture time, which is what an operator
 * actually has, while paging stays on the id (see {@code FrameEventRepository.findCapturedBetween}
 * for why the two differ).
 *
 * <p><strong>Reading is capped, not streamed.</strong> A page is materialised whole — rows, then
 * base64 — so the cap is a memory decision, not a politeness one: at {@link #MAX_LIMIT} and a 40 KB
 * frame that is ~20 MB of rows plus ~27 MB of base64 <em>per concurrent request</em>, against a
 * container sized 512 MB in {@code docker-compose.all-services.yml}. Raising it without raising
 * {@code mem_limit} is how this endpoint takes the hub down, and the hub going down stops the
 * recording. A caller that wants a whole hour asks for it a page at a time via {@code nextAfter}.
 *
 * <p>Deliberately no interaction with retention: this reads what is in {@code frame_events} at the
 * moment of the query, and {@code jarvis-retention-worker} may delete rows out from under a paging
 * caller. Pages are read forward by id, so that shows up as a short page, never as a repeat.
 */
@Service
public class FrameQueryService {
    /** Frames per page when the caller does not say. */
    static final int DEFAULT_LIMIT = 100;
    /** The ceiling a caller may ask for. See the memory note above before raising it. */
    static final int MAX_LIMIT = 500;

    /**
     * Stand-ins for an open-ended bound. Not {@code Instant.MIN}/{@code MAX}: those are year
     * ±1000000000 and overflow a Postgres {@code timestamp}, which fails the query rather than
     * matching everything. No frame can predate the epoch — {@code capturedAt} is stamped with
     * {@code Instant.now()} at ingest — so these really are open ends in practice.
     */
    private static final Instant BEGINNING = Instant.EPOCH;
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");

    private final FrameEventRepository events;

    public FrameQueryService(FrameEventRepository events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    /**
     * One page of the window.
     *
     * @param source restricts to one camera; null spans every source.
     * @param from   inclusive lower bound on capture time; null means the oldest frame kept.
     * @param to     <em>exclusive</em> upper bound; null means now. Half-open so a caller walking
     *               adjacent windows neither repeats nor misses the frame on the boundary.
     * @param after  exclusive frame-id cursor — pass the previous page's {@code nextAfter}.
     * @param limit  frames in this page, 1..{@value #MAX_LIMIT}.
     */
    public Map<String, Object> range(String source, Instant from, Instant to, Long after,
            Integer limit) {
        int size = pageSize(limit);
        Instant lower = from == null ? BEGINNING : from;
        Instant upper = to == null ? END_OF_TIME : to;
        if (lower.isAfter(upper)) {
            throw new IllegalArgumentException("from must not be after to");
        }
        long cursor = after == null ? 0L : Math.max(0L, after);

        // One more than asked for: if it comes back, there is another page, and it costs one extra
        // row rather than a second count query over a table this size.
        PageRequest page = PageRequest.of(0, size + 1);
        List<FrameEventEntity> found = source == null
                ? events.findCapturedBetween(lower, upper, cursor, page)
                : events.findCapturedBetweenBySource(lower, upper, cursor, source, page);

        boolean hasMore = found.size() > size;
        List<FrameEventEntity> pageOfFrames = hasMore ? found.subList(0, size) : found;

        List<Map<String, Object>> frames = new ArrayList<>(pageOfFrames.size());
        for (FrameEventEntity frame : pageOfFrames) {
            frames.add(FrameMessage.of(frame));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        // Echoed back so a response is self-describing in a log or a saved file — including the
        // bounds the caller left to default, which are the ones they most need told.
        response.put("source", source);
        response.put("from", from == null ? null : from.toString());
        response.put("to", to == null ? null : to.toString());
        response.put("after", after);
        response.put("limit", size);
        response.put("returned", frames.size());
        response.put("hasMore", hasMore);
        // Null rather than absent when the window is exhausted: a caller loops while it is set.
        response.put("nextAfter",
                hasMore ? pageOfFrames.get(pageOfFrames.size() - 1).getId() : null);
        response.put("frames", frames);
        return response;
    }

    private static int pageSize(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            // Rejected rather than clamped: a caller asking for 5000 and silently getting 500 reads
            // the short page as "the window ended here" and stops early, losing frames without an
            // error anywhere. Paging is the caller's job to do, so it has to be told to do it.
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }
}
