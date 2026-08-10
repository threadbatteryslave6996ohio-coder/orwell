package dev.orwell.google.gmail;

import dev.orwell.google.gmail.entity.EmailMessageEntity;
import dev.orwell.google.gmail.entity.UserEntity;
import dev.orwell.google.gmail.entity.WebhookSubscriptionEntity;
import dev.orwell.google.gmail.repository.EmailAttachmentRepository;
import dev.orwell.google.gmail.repository.EmailHeaderRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    /**
     * A {@link MailPayloads} over empty header and attachment tables — the shape of a plain text
     * message, which is what the delivery tests are about. Content assembly has its own coverage in
     * {@link MailParserTest} and the integration tests.
     */
    static MailPayloads payloads() {
        EmailHeaderRepository headers = mock(EmailHeaderRepository.class);
        when(headers.findByMessageIdInOrderByMessageIdAscOrdinalAsc(any())).thenReturn(List.of());
        EmailAttachmentRepository attachments = mock(EmailAttachmentRepository.class);
        when(attachments.findByMessageIdInOrderByMessageIdAscPartIndexAsc(any()))
                .thenReturn(List.of());
        return new MailPayloads(headers, attachments, "", "");
    }

    /**
     * Runs the callback and commits, without a database. Enough for the unit tests, which mock the
     * repositories: what they assert is what leaves the service, not that a rollback works.
     */
    static PlatformTransactionManager noOpTransactions() {
        return new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
    }

    /** A parsed message with no HTML part and no attachments, as the plain-text path produces. */
    static ParsedMail parsed(String messageId, String subject) {
        return new ParsedMail(messageId, subject, "alice@example.com", "owner@example.com",
                Instant.parse("2026-06-27T15:30:45Z").toEpochMilli(), "body", "",
                List.of(new ParsedMail.ParsedHeader("Subject", subject)), List.of(),
                new byte[0], 0L, false);
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
                        "", 0L, false, Instant.now()),
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
