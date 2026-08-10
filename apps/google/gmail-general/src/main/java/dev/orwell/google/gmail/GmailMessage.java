package dev.orwell.google.gmail;

/**
 * One received mail, as handed to webhook subscribers.
 *
 * <p>{@code account} is the mailbox the message was polled from. It exists so a receiver can tell
 * whose mail it is being handed: a per-mailbox subscription only ever delivers one account, but the
 * legacy {@code GMAIL_WEBHOOK_CLIENTS} broadcast delivers every mailbox to the same URL, and
 * without this field those receivers cannot distinguish them.
 */
public record GmailMessage(String id, String account, String subject, String from,
                           String to, long receivedAt, String body) {}
