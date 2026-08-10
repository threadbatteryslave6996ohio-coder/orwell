package dev.orwell.bucket.detection;

import dev.orwell.auth.AuthenticationContext;
import dev.orwell.bootstrap.auth.RequireAuthentication;
import dev.orwell.bucket.detection.entity.FrameEventEntity;
import dev.orwell.bucket.detection.entity.FrameSubscriptionEntity;
import dev.orwell.bucket.detection.repository.FrameEventRepository;
import dev.orwell.bucket.detection.repository.FrameSubscriptionRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
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
 * Frame subscriptions, scoped to the calling client.
 *
 * <p>Every route resolves the owner from the authenticated caller's client id; no route takes a
 * client id as a parameter, so a consumer can only register, list and delete its own
 * subscriptions. A delete addressed to another client's row answers 404 rather than 403, so ids
 * cannot be probed.
 */
@RestController
@RequestMapping("/subscriptions")
@RequireAuthentication
public class SubscriptionController {
    private final FrameSubscriptionRepository subscriptions;
    private final FrameEventRepository events;
    private final ObjectProvider<AuthenticationContext> authenticationContextProvider;

    public SubscriptionController(
            FrameSubscriptionRepository subscriptions,
            FrameEventRepository events,
            ObjectProvider<AuthenticationContext> authenticationContextProvider) {
        this.subscriptions = Objects.requireNonNull(subscriptions, "subscriptions");
        this.events = Objects.requireNonNull(events, "events");
        this.authenticationContextProvider =
                Objects.requireNonNull(authenticationContextProvider, "authenticationContextProvider");
    }

    /** The caller's own subscriptions, oldest first. */
    @GetMapping
    public List<SubscriptionResponse> listSubscriptions() {
        return subscriptions.findByClientIdOrderByIdAsc(currentClientId()).stream()
                .map(SubscriptionResponse::from).toList();
    }

    /**
     * Subscribes a URL to the frame stream, optionally scoped to one source. Re-posting the same
     * (url, source) pair is a 409 rather than a second row, so a retried registration cannot
     * double every delivery.
     *
     * <p>The cursor starts at the current head, so a new subscription receives what arrives from
     * now on rather than replaying whatever is still inside the retention window.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SubscriptionResponse createSubscription(@Valid @RequestBody CreateSubscriptionRequest request) {
        String clientId = currentClientId();
        String url = validated(request.url().trim());
        String source = request.source() == null || request.source().isBlank()
                ? null : request.source().trim();
        if (subscriptions.existsByClientIdAndUrlAndSource(clientId, url, source)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This URL is already subscribed for that source.");
        }
        long head = events.findTopByOrderByIdDesc().map(FrameEventEntity::getId).orElse(0L);
        return SubscriptionResponse.from(subscriptions.save(
                new FrameSubscriptionEntity(clientId, url, source, head, Instant.now())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSubscription(@PathVariable Long id) {
        FrameSubscriptionEntity subscription = subscriptions
                .findByIdAndClientId(id, currentClientId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No such subscription."));
        subscriptions.delete(subscription);
    }

    private String currentClientId() {
        AuthenticationContext context = authenticationContextProvider.getIfAvailable();
        if (context == null || !context.authenticated() || context.clientId() == null) {
            // The @RequireAuthentication guard runs first, so reaching here means the context bean
            // is missing rather than the caller being anonymous — a wiring fault, not a 401.
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "No authenticated client in context.");
        }
        return context.clientId();
    }

    /**
     * Only absolute http(s) URLs are accepted. A relative or opaque URL would fail much later,
     * inside a delivery round, where it would look like a subscriber outage rather than a typo.
     */
    private static String validated(String url) {
        URI parsed;
        try {
            parsed = new URI(url);
        } catch (URISyntaxException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "url is not a valid URI.");
        }
        String scheme = parsed.getScheme() == null
                ? "" : parsed.getScheme().toLowerCase(Locale.ROOT);
        if (!parsed.isAbsolute() || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "url must be an absolute http or https URL.");
        }
        return url;
    }

    public record CreateSubscriptionRequest(@NotBlank String url, String source) {
    }

    public record SubscriptionResponse(
            Long id, String url, String source, boolean active, long lastDeliveredId,
            Instant createdAt) {
        static SubscriptionResponse from(FrameSubscriptionEntity entity) {
            return new SubscriptionResponse(entity.getId(), entity.getUrl(), entity.getSource(),
                    entity.isActive(), entity.getLastDeliveredId(), entity.getCreatedAt());
        }
    }
}
