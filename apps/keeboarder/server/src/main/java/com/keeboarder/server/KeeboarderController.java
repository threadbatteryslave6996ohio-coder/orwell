package com.keeboarder.server;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("${keeboarder.server.route-prefix:}")
public class KeeboarderController {
    private final KeeboarderService service;
    private final ClientAuthenticator authenticator;

    public KeeboarderController(KeeboarderService service, ClientAuthenticator authenticator) {
        this.service = service;
        this.authenticator = authenticator;
    }

    @GetMapping(value = "/clients", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> clients(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        if (!isAuthenticated(clientId, authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        return ResponseEntity.ok(service.connectedClientsJson());
    }

    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> send(
            @RequestBody SendRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Client-Id", required = false) String clientId
    ) {
        if (!isAuthenticated(clientId, authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("status", "unauthorized"));
        }
        if (request.toClientId() == null || request.content() == null) {
            return ResponseEntity.badRequest().body(Map.of("status", "invalid_request"));
        }
        if (!service.send(request.toClientId(), clientId, request.content())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("status", "failed", "reason", "target_not_connected"));
        }
        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    public record SendRequest(String toClientId, String content, String fromClientId) {
    }

    private boolean isAuthenticated(String clientId, String authorization) {
        if (clientId == null || clientId.isBlank() || authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, "Bearer ".length())) {
            return false;
        }
        String token = authorization.substring("Bearer ".length()).trim();
        return !token.isBlank() && authenticator.isTokenValidForClient(clientId, token);
    }
}
