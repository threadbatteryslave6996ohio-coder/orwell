package dev.orwell.google.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import dev.orwell.bootstrap.RequireAuthentication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Gmail notifications arrive at {@code /gmail/pubsub}; watch registration at {@code /gmail/watch}. */
@RestController
public class GmailController {
    private final GmailService service;

    public GmailController(GmailService service) {
        this.service = service;
    }

    // Raw bytes on purpose: any processing problem (including a bad envelope) is a 500
    // "processing failed" per the original contract, not the invalid-json 400.
    @PostMapping("/gmail/pubsub")
    public ResponseEntity<?> pubsub(@RequestBody(required = false) byte[] body) {
        try {
            service.handlePubsub(body == null ? new byte[0] : body);
            return ResponseEntity.ok().build();
        } catch (Exception exception) {
            exception.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "processing failed"));
        }
    }

    @RequireAuthentication
    @PostMapping("/gmail/watch")
    public ResponseEntity<?> watch() {
        try {
            JsonNode result = service.watch();
            return ResponseEntity.ok(result);
        } catch (GmailService.MissingPubsubTopicException exception) {
            // The one genuine client-addressable precondition; everything else (missing OAuth
            // tokens, Gmail API failures) is a server-side problem and must surface as a 500.
            return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
        } catch (Exception exception) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "watch failed"));
        }
    }
}
