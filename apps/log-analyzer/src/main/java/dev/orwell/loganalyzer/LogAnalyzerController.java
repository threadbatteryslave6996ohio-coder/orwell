package dev.orwell.loganalyzer;

import dev.orwell.http.EndpointResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class LogAnalyzerController {
    private final LogAnalyzerEndpoint endpoint;

    public LogAnalyzerController(LogAnalyzerEndpoint endpoint) {
        this.endpoint = endpoint;
    }

    @PostMapping("/run-once")
    public ResponseEntity<Map<String, Object>> runOnce() {
        EndpointResponse<Map<String, Object>> response = endpoint.runOnce();
        return ResponseEntity.status(response.status()).body(response.body());
    }
}
