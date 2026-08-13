package dev.orwell.bucket.hub;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
public class HubController {
    private final HubEndpoint endpoint;
    private final FrameHub hub;

    public HubController(HubEndpoint endpoint, FrameHub hub) {
        this.endpoint = endpoint;
        this.hub = hub;
    }

    /** Ingest: store the frame and relay it to every client currently connected to the stream. */
    // Malformed JSON is handled by the shared InvalidJsonBodyAdvice (400 {"success":false,...}).
    @PostMapping("/frames")
    public ResponseEntity<Map<String, Object>> frames(@RequestBody Map<String, Object> payload) {
        return endpoint.frames(payload);
    }

    /**
     * The range query: stored frames captured in {@code [from, to)}, oldest first, one page at a
     * time. Both bounds are optional — omitting them walks the whole retained window.
     *
     * <p>Companion to the stream rather than a variant of it. The stream is for following a feed
     * and resumes by frame id; this is for going back to a time somebody names, so its window is in
     * capture time. Paging is still by id: pass the previous response's {@code nextAfter} as
     * {@code after} and stop when {@code hasMore} is false.
     */
    @GetMapping(value = "/frames", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> framesInRange(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String after,
            @RequestParam(required = false) String limit) {
        return endpoint.range(
                trimmedOrNull(source),
                trimmedOrNull(from),
                trimmedOrNull(to),
                trimmedOrNull(after),
                trimmedOrNull(limit));
    }

    /**
     * The stream. Replays whatever the client missed from the stored frames, then holds the
     * connection open and emits a {@code frame} event per new frame.
     *
     * <p>Where it resumes from, in order of precedence: the standard SSE {@code Last-Event-ID}
     * header (which a browser's {@code EventSource} sends automatically on reconnect), an explicit
     * {@code from} frame id, the stored cursor for {@code subscription}, and finally the current
     * head — so a plain connection sees what happens next rather than the whole retention window.
     */
    @GetMapping(value = "/frames/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String subscription,
            @RequestParam(required = false) Long from,
            @RequestHeader(value = "Last-Event-ID", required = false) Long lastEventId) {
        return hub.connect(
                trimmedOrNull(source),
                trimmedOrNull(subscription),
                lastEventId != null ? lastEventId : from);
    }

    private static String trimmedOrNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
