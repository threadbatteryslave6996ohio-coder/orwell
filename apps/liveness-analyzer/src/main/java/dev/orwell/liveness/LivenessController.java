package dev.orwell.liveness;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class LivenessController {
    private final LivenessService service;

    public LivenessController(LivenessService service) {
        this.service = service;
    }

    /** Runs a liveness check immediately, off the schedule, for probing and tests. */
    @PostMapping("/run-once")
    public ResponseEntity<Map<String, Object>> runOnce() {
        try {
            Map<String, Object> result = service.checkOnce();
            // A check already in progress is not a completed run; callers can retry a 409.
            int status = Boolean.TRUE.equals(result.get("skipped")) ? 409 : 200;
            return ResponseEntity.status(status).body(result);
        } catch (Exception exception) {
            service.recordRunOnceFailure(exception);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("error", exception.getMessage());
            return ResponseEntity.status(500).body(body);
        }
    }
}
