package dev.orwell.alerting;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class AlertController {
    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    // Malformed JSON is handled by the shared InvalidJsonBodyAdvice (400 {"success":false,...}).
    @PostMapping("/alerts")
    public Map<String, Object> alerts(@RequestBody Map<String, Object> alert) {
        return service.handleAlert(alert);
    }
}
