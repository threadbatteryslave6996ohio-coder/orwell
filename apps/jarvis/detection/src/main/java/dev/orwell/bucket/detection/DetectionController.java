package dev.orwell.bucket.detection;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DetectionController {
    private final DetectionService service;

    public DetectionController(DetectionService service) {
        this.service = service;
    }

    // Malformed JSON is handled by the shared InvalidJsonBodyAdvice (400 {"success":false,...}).
    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detect(@RequestBody Map<String, Object> payload) {
        try {
            return ResponseEntity.ok(service.detect(payload));
        } catch (DetectionService.InvalidFrameException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", exception.getMessage()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("success", false, "error", String.valueOf(exception.getMessage())));
        }
    }
}
