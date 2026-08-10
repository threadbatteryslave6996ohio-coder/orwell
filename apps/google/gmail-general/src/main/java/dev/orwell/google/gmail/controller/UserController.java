package dev.orwell.google.gmail.controller;

import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.UserSecretEntity;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.UserSecretRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Administrative management of the mailboxes this service polls.
 *
 * <p>Unlike {@link MailController}, these routes are not scoped to the caller's own mailbox — any
 * authenticated caller can create a user and set any user's IMAP password. The bearer token is the
 * only thing standing between a caller and every mailbox in the system, so the credential used to
 * reach these routes should not be handed to ordinary mail consumers.
 */
@RestController
@RequestMapping("${gmail.route-prefix:}/users")
@RequireAuthentication
public class UserController {
    private final UserRepository users;
    private final UserSecretRepository secrets;

    public UserController(UserRepository users, UserSecretRepository secrets) {
        this.users = Objects.requireNonNull(users, "users");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
    }

    /** Every registered mailbox. Secrets are not included. */
    @GetMapping
    public List<UserResponse> listUsers() {
        return users.findAll().stream().map(UserResponse::from).toList();
    }

    /**
     * Registers a mailbox. The user is pollable only once its secret is set; until then the poller
     * skips it with a warning.
     *
     * <p>{@code email} and {@code clientId} are both unique. A duplicate is a 409 rather than a
     * 500 from the database constraint, so the caller can tell "already registered" from a fault.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {
        String email = request.email().trim();
        String clientId = request.clientId().trim();
        if (users.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with this email already exists.");
        }
        if (users.existsByClientId(clientId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A user with this client id already exists.");
        }
        return UserResponse.from(users.save(new UserEntity(email, clientId, Instant.now())));
    }

    /**
     * Sets or replaces a user's IMAP password. Idempotent: the one-to-one {@code secrets} row is
     * created on first call and updated afterwards, so this can never leave a user with two.
     * Returns {@code 204 No Content} — the value is write-only and is never echoed back.
     */
    @PutMapping("/{id}/secret")
    public ResponseEntity<Void> setSecret(
            @PathVariable("id") Long id, @Valid @RequestBody SetSecretRequest request) {
        UserEntity user = users.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such user."));
        UserSecretEntity secret = secrets.findByUserId(user.getId())
                .map(existing -> {
                    existing.update(request.imapPassword(), Instant.now());
                    return existing;
                })
                .orElseGet(() -> new UserSecretEntity(user, request.imapPassword(), Instant.now()));
        secrets.save(secret);
        return ResponseEntity.noContent().build();
    }
}
