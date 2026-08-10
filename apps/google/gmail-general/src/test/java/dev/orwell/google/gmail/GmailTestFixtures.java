package dev.orwell.google.gmail;

import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;

import java.lang.reflect.Field;
import java.time.Instant;

/**
 * Entities with database-assigned ids, for the unit tests that mock their repositories.
 *
 * <p>Fan-out and delivery are keyed by id, so these fixtures need one without a round trip. The
 * fields are set reflectively rather than adding setters that production code would have no reason
 * to call.
 */
final class GmailTestFixtures {
    private GmailTestFixtures() {
    }

    static UserEntity user(long id, String email, String clientId) {
        return withId(new UserEntity(email, clientId, Instant.now()), UserEntity.class, id);
    }

    static WebhookSubscriptionEntity subscription(
            long id, UserEntity user, String url, long lastDeliveredId) {
        return withId(new WebhookSubscriptionEntity(user, url, lastDeliveredId, Instant.now()),
                WebhookSubscriptionEntity.class, id);
    }

    static EmailMessageEntity mail(long id, UserEntity user, String messageId, String subject) {
        return withId(new EmailMessageEntity(user, messageId, id, subject, "alice@example.com",
                        user.getEmail(), Instant.parse("2026-06-27T15:30:45Z"), "body " + subject,
                        Instant.now()),
                EmailMessageEntity.class, id);
    }

    private static <T> T withId(T entity, Class<?> type, long id) {
        try {
            Field field = type.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not seed an id on " + type.getSimpleName(), exception);
        }
        return entity;
    }
}
