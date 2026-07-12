package dev.orwell.keeboarder.server.http;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.keeboarder.server.service.KeeboarderService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("${keeboarder.server.route-prefix:}")
public class KeeboarderController {
    private final KeeboarderService service;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    public KeeboarderController(KeeboarderService service, ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.service = service;
        this.authenticationContextProvider = authenticationContextProvider;
    }

    @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> clients() {
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        if (!authenticationContext.authenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(service.connectedClientsJson());
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> send(@RequestBody SendRequest request) {
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        if (!authenticationContext.authenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
        }
        if (request.toClientId() == null || request.content() == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "invalid_request"));
        }
        if (!service.send(request.toClientId(), authenticationContext.clientId(), request.content())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "failed", "reason", "target_not_connected"));
        }
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    public record SendRequest(String toClientId, String content) {
    }
}
