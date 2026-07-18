package dev.orwell.alerting;

import dev.orwell.http.EndpointResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AlertController {
    private final AlertEndpoint endpoint;

    public AlertController(AlertEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    // Malformed JSON is handled by the shared InvalidJsonBodyAdvice (400 {"success":false,...}).
    @PostMapping("/alerts")
    public ResponseEntity<Map<String, Object>> alerts(@RequestBody Map<String, Object> alert) {
        EndpointResponse<Map<String, Object>> response = endpoint.alert(alert);
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
