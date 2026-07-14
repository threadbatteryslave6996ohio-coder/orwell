package dev.orwell.loganalyzer;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class LogAnalyzerController {
    private final LogAnalyzerService service;

    public LogAnalyzerController(LogAnalyzerService service) {
        this.service = service;
    }

    @PostMapping("/run-once")
    public ResponseEntity<Map<String, Object>> runOnce() {
        try {
            Map<String, Object> result = service.pollOnce();
            // A poll already in progress is not a completed run; signal 409 so a caller
            // forcing an on-demand check can tell nothing ran and retry.
            HttpStatus status = Boolean.TRUE.equals(result.get("skipped")) ? HttpStatus.CONFLICT : HttpStatus.OK;
            return ResponseEntity.status(status).body(result);
        } catch (Exception exception) {
            service.recordRunOnceFailure(exception);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", String.valueOf(exception.getMessage())));
        }
    }
}
