package dev.orwell.google.gmail.controller;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

/**
 * Read-only access to mail stored by {@link dev.orwell.google.gmail.ImapMailPoller}.
 *
 * <p>Which mailbox a request reads is derived from the authenticated caller: the bearer token's
 * client id is matched against {@code users.client_id}. There is deliberately no parameter that
 * selects a user, so one consumer cannot read another's mail even by guessing an id — the only
 * mailbox reachable is the one the caller's own identity owns.
 *
 * <p>{@code id} (see {@link dev.orwell.google.gmail.entity.EmailMessageEntity}) is assigned in
 * insertion order, so it doubles as a consumption cursor: a consumer polls {@code GET
 * /mails/latest} to get started, then repeatedly calls {@code GET /mails/latest?checkpoint=<last
 * id it saw>} to fetch only what arrived since.
 */
@RestController
@RequestMapping("${gmail.route-prefix:}/mails")
@RequireAuthentication
public class MailController {
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 500;

    private final EmailMessageRepository repository;
    private final UserRepository users;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    public MailController(
            EmailMessageRepository repository,
            UserRepository users,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.users = Objects.requireNonNull(users, "users");
        this.authenticationContextProvider =
                Objects.requireNonNull(authenticationContextProvider, "authenticationContextProvider");
    }

    /** The caller's most recently received mail first, bounded by {@code limit} (default 50, max 500). */
    @GetMapping
    public List<MailResponse> listMails(@RequestParam(name = "limit", required = false) Integer limit) {
        Pageable page = PageRequest.of(0, boundedLimit(limit));
        return repository.findAllByUserIdOrderByIdDesc(currentUser().getId(), page).stream()
                .map(MailResponse::from).toList();
    }

    /**
     * With no {@code checkpoint}: the caller's single most recent mail, or {@code 204 No Content}
     * if their mailbox is empty. With {@code checkpoint=<id>}: every mail of theirs with {@code id}
     * greater than it, oldest first, bounded by {@code limit} (default 50, max 500) — the
     * incremental-consumption path.
     */
    @GetMapping("/latest")
    public ResponseEntity<?> latest(
            @RequestParam(name = "checkpoint", required = false) Long checkpoint,
            @RequestParam(name = "limit", required = false) Integer limit) {
        Long userId = currentUser().getId();
        if (checkpoint == null) {
            return repository.findTopByUserIdOrderByIdDesc(userId)
                    .<ResponseEntity<?>>map(entity -> ResponseEntity.ok(MailResponse.from(entity)))
                    .orElseGet(() -> ResponseEntity.noContent().build());
        }
        Pageable page = PageRequest.of(0, boundedLimit(limit));
        List<MailResponse> mails = repository
                .findByUserIdAndIdGreaterThanOrderByIdAsc(userId, checkpoint, page).stream()
                .map(MailResponse::from).toList();
        return ResponseEntity.ok(mails);
    }

    /**
     * The user the authenticated caller owns. {@link RequireAuthentication} has already rejected
     * unauthenticated requests, so reaching this with no matching row means a valid client id that
     * no mailbox belongs to — a configuration gap, reported as 403 rather than 404 so it cannot be
     * used to probe which client ids exist.
     */
    private UserEntity currentUser() {
        String clientId = authenticationContextProvider.getObject().clientId();
        return users.findByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "No mailbox is registered for this client."));
    }

    private static int boundedLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
