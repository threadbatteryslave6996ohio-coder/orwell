package dev.orwell.google.gmail.controller;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailMessageRepository;
import dev.orwell.google.gmail.repository.UserRepository;
import dev.orwell.google.gmail.repository.WebhookSubscriptionRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Webhook subscriptions, scoped to the caller's own mailbox.
 *
 * <p>Like {@link MailController} and unlike {@link UserController}, every route here resolves the
 * mailbox from the authenticated caller's client id. There is no parameter that names a user, so a
 * consumer can only subscribe its own mailbox and can only list or delete its own subscriptions —
 * registering a URL against someone else's mail is not expressible.
 */
@RestController
@RequestMapping("${gmail.route-prefix:}/subscriptions")
@RequireAuthentication
public class SubscriptionController {
    private final WebhookSubscriptionRepository subscriptions;
    private final EmailMessageRepository mails;
    private final UserRepository users;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    public SubscriptionController(
            WebhookSubscriptionRepository subscriptions,
            EmailMessageRepository mails,
            UserRepository users,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.mails = Objects.requireNonNull(mails, "mails");
        this.users = Objects.requireNonNull(users, "users");
        this.authenticationContextProvider =
                Objects.requireNonNull(authenticationContextProvider, "authenticationContextProvider");
    }

    /** The caller's own subscriptions, oldest first. */
    @GetMapping
    public List<SubscriptionResponse> listSubscriptions() {
        return subscriptions.findByUserIdOrderByIdAsc(currentUser().getId()).stream()
                .map(SubscriptionResponse::from).toList();
    }

    /**
     * Subscribes a URL to the caller's mailbox. Re-posting a URL the caller already subscribed is a
     * 409 rather than a second row, so a retried registration cannot double every delivery.
     *
     * <p>The delivery cursor starts at the mailbox's current head, so a new subscription receives
     * what arrives from now on rather than replaying everything already stored — the same rule a
     * newly registered mailbox follows. Subscribe before you expect to receive anything.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse createSubscription(
            @Valid @RequestBody CreateSubscriptionRequest request) {
        UserEntity user = currentUser();
        String url = validated(request.url().trim());
        if (subscriptions.existsByUserIdAndUrl(user.getId(), url)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This URL is already subscribed to your mailbox.");
        }
        long head = mails.findTopByUserIdOrderByIdDesc(user.getId())
                .map(EmailMessageEntity::getId).orElse(0L);
        return SubscriptionResponse.from(subscriptions.save(
                new WebhookSubscriptionEntity(user, url, head, Instant.now())));
    }

    /**
     * Removes one of the caller's subscriptions. A subscription that exists but belongs to another
     * user is reported as 404 exactly like one that does not exist, so the route cannot be used to
     * discover which ids are taken.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSubscription(@PathVariable("id") Long id) {
        WebhookSubscriptionEntity subscription = subscriptions
                .findByIdAndUserId(id, currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such subscription."));
        subscriptions.delete(subscription);
        return ResponseEntity.noContent().build();
    }

    /**
     * Rejects anything the delivery path could not POST to. {@code URI.create} in
     * {@link dev.orwell.google.gmail.GmailService} would otherwise throw per message, turning a bad
     * registration into a permanent per-delivery error nobody sees but the log.
     */
    private static String validated(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The URL is not a valid URI.");
        }
        String scheme = parsed.getScheme() == null
                ? null : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The URL must be absolute and use http or https.");
        }
        if (parsed.getHost() == null || parsed.getHost().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The URL must name a host.");
        }
        return url;
    }

    /**
     * The mailbox the authenticated caller owns — the same rule {@link MailController} applies to
     * reads, including the 403 for a valid client id that no mailbox belongs to.
     */
    private UserEntity currentUser() {
        String clientId = authenticationContextProvider.getObject().clientId();
        return users.findByClientId(clientId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN, "No mailbox is registered for this client."));
    }
}
