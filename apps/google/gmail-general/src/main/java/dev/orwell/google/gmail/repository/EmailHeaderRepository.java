package dev.orwell.google.gmail.repository;

import dev.orwell.google.gmail.entity.EmailHeaderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/**
 * Headers are loaded for a whole page of messages in one query rather than per message: a mail
 * routinely carries thirty or more header rows, so a page of fifty would otherwise be fifty extra
 * round trips to render one list.
 *
 * <p>Unscoped by user on purpose — a header row is reachable only through a message id the caller
 * has already been authorised for, and the scoping lives in {@code EmailMessageRepository} where
 * the ownership actually is.
 */
public interface EmailHeaderRepository extends JpaRepository<EmailHeaderEntity, Long> {
    List<EmailHeaderEntity> findByMessageIdInOrderByMessageIdAscOrdinalAsc(Collection<Long> messageIds);
}
