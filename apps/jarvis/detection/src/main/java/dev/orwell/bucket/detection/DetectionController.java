package dev.orwell.bucket.detection;

import dev.orwell.http.EndpointResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class DetectionController {
    private final DetectionEndpoint endpoint;

    public DetectionController(DetectionEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    // Malformed JSON is handled by the shared InvalidJsonBodyAdvice (400 {"success":false,...}).
    @PostMapping("/detect")
    public ResponseEntity<Map<String, Object>> detect(@RequestBody Map<String, Object> payload) {
        EndpointResponse<Map<String, Object>> response = endpoint.detect(payload);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
