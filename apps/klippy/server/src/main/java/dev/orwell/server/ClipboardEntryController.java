package dev.orwell.server;

import dev.orwell.auth.AuthenticationStrategy;
import dev.orwell.logging.CustomLogger;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("${clippy.server.route-prefix:}")
public class ClipboardEntryController {
    private static final CustomLogger LOGGER = new CustomLogger("clippy-server");

    private final ClipboardEntryRepository repository;
    private final AuthenticationStrategy authenticationStrategy;

    public ClipboardEntryController(
            ClipboardEntryRepository repository,
            AuthenticationStrategy authenticationStrategy
    ) {
        this.repository = repository;
        this.authenticationStrategy = authenticationStrategy;
    }

    @PostMapping("/clipboard")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized ClipboardEntryResponse create(
            @Valid @RequestBody ClipboardEntryRequest request,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        String token = bearerToken(authorization);
        if (!authenticationStrategy.isTokenValidForClient(request.clientId(), token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
        }

        Instant timestamp = (request.timestamp() == null ? Instant.now() : request.timestamp())
                .truncatedTo(ChronoUnit.MICROS);
        ClipboardEntry existing = repository
                .findFirstByClientIdAndTimestampAndContentOrderByIdAsc(
                        request.clientId(), timestamp, request.content())
                .orElse(null);
        if (existing != null) {
            return response(existing);
        }

        ClipboardEntry saved = repository.save(new ClipboardEntry(
                request.clientId(),
                request.content(),
                timestamp
        ));
        logClipboardEntrySaved(saved);
        return new ClipboardEntryResponse(saved.getId(), saved.getClientId(), saved.getTimestamp());
    }

    @GetMapping("/clipboard")
    public List<ClipboardEntryDetailsResponse> findWithinTimeframe(
            @RequestParam("clientId") String clientId,
            @RequestParam("from") Instant from,
            @RequestParam("to") Instant to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "afterTimestamp", required = false) Instant afterTimestamp,
            @RequestParam(value = "afterId", required = false) Long afterId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization
    ) {
        String token = bearerToken(authorization);
        if (!authenticationStrategy.isTokenValidForClient(clientId, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid client token.");
        }
        if (from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before or equal to to.");
        }

        if ((afterTimestamp == null) != (afterId == null)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "afterTimestamp and afterId must be provided together.");
        }
        if (limit != null && (limit < 1 || limit > 1_000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and 1000.");
        }

        List<ClipboardEntry> entries;
        if (limit == null) {
            entries = repository.findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(clientId, from, to);
        } else if (afterTimestamp == null) {
            entries = repository.findByClientIdAndTimestampBetweenOrderByTimestampAscIdAsc(
                    clientId, from, to, PageRequest.of(0, limit));
        } else {
            entries = repository.findTimeframePage(
                    clientId, from, to, afterTimestamp, afterId, PageRequest.of(0, limit));
        }
        return entries
                .stream()
                .map(ClipboardEntryDetailsResponse::from)
                .toList();
    }

    private static void logClipboardEntrySaved(ClipboardEntry saved) {
        try {
            LOGGER.log("Added clipboard entry for clientId=" + saved.getClientId()
                    + ", entryId=" + saved.getId()
                    + " at " + saved.getTimestamp());
        } catch (RuntimeException exception) {
            // Audit logging is best-effort; a logging failure must not reject the write.
        }
    }

    private static ClipboardEntryResponse response(ClipboardEntry entry) {
        return new ClipboardEntryResponse(entry.getId(), entry.getClientId(), entry.getTimestamp());
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }

        String prefix = "Bearer ";
        if (!authorization.regionMatches(true, 0, prefix, 0, prefix.length())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Expected bearer token.");
        }

        String token = authorization.substring(prefix.length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token.");
        }
        return token;
    }
}
