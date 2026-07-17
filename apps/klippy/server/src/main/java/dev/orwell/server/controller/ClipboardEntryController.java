package dev.orwell.server.controller;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.logging.CustomLogger;
import dev.orwell.server.dto.ClipboardEntryDetailsResponse;
import dev.orwell.server.dto.ClipboardEntryRequest;
import dev.orwell.server.dto.ClipboardEntryResponse;
import dev.orwell.server.model.ClipboardEntry;
import dev.orwell.server.repository.ClipboardEntryRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@RestController
public class ClipboardEntryController {
    private static final CustomLogger LOGGER = new CustomLogger("clippy-server");

    private final ClipboardEntryRepository repository;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    public ClipboardEntryController(
            ClipboardEntryRepository repository,
            ObjectProvider<AuthenticationContext> authenticationContextProvider
    ) {
        this.repository = repository;
        this.authenticationContextProvider = authenticationContextProvider;
    }

    @PostMapping("/clipboard")
    @ResponseStatus(HttpStatus.CREATED)
    public synchronized ClipboardEntryResponse create(
            @Valid @RequestBody ClipboardEntryRequest request
    ) {
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        if (!authenticationContext.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing client identity.");
        }
        if (!Objects.equals(request.clientId(), authenticationContext.clientId())) {
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
        try {
            LOGGER.log("Added clipboard entry for clientId=" + saved.getClientId()
                    + ", entryId=" + saved.getId()
                    + " at " + saved.getTimestamp());
        } catch (RuntimeException exception) {
            // Audit logging is best-effort; a logging failure must not reject the write.
        }
        return new ClipboardEntryResponse(saved.getId(), saved.getClientId(), saved.getTimestamp());
    }

    @GetMapping("/clipboard")
    public List<ClipboardEntryDetailsResponse> findWithinTimeframe(
            @RequestParam("clientId") String clientId,
            @RequestParam("from") Instant from,
            @RequestParam("to") Instant to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "afterTimestamp", required = false) Instant afterTimestamp,
            @RequestParam(value = "afterId", required = false) Long afterId
    ) {
        AuthenticationContext authenticationContext = authenticationContextProvider.getObject();
        if (!authenticationContext.authenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing client identity.");
        }
        if (!Objects.equals(clientId, authenticationContext.clientId())) {
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

    private static ClipboardEntryResponse response(ClipboardEntry entry) {
        return new ClipboardEntryResponse(entry.getId(), entry.getClientId(), entry.getTimestamp());
    }
}
