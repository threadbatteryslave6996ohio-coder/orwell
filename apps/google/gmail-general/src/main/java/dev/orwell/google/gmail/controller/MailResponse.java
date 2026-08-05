package dev.orwell.google.gmail.controller;

import dev.orwell.google.gmail.entity.EmailMessageEntity;

import java.time.Instant;

public record MailResponse(Long id, String messageId, String threadId, String subject,
                            String from, String to, Instant receivedAt, String body) {
    public static MailResponse from(EmailMessageEntity entity) {
        return new MailResponse(entity.getId(), entity.getMessageId(), entity.getThreadId(),
                entity.getSubject(), entity.getFromAddress(), entity.getToAddress(),
                entity.getReceivedAt(), entity.getBody());
    }
}
