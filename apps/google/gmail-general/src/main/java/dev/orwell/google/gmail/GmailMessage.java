package dev.orwell.google.gmail;

public record GmailMessage(String id, String threadId, String subject, String from,
                           String to, long receivedAt, String body) {}
