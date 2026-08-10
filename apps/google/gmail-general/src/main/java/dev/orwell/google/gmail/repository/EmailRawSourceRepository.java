package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.EmailRawSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Reads whole message sources, so every call here is potentially a
 * {@code GMAIL_MAX_MESSAGE_BYTES}-sized load. Only the attachment-download path uses it; nothing
 * that serves a list of messages should.
 */
public interface EmailRawSourceRepository extends JpaRepository<EmailRawSourceEntity, Long> {
    Optional<EmailRawSourceEntity> findByMessageId(Long messageId);
}
