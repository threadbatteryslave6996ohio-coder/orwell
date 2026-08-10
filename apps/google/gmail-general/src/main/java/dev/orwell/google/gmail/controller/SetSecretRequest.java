package dev.orwell.google.gmail.controller;

import jakarta.validation.constraints.NotBlank;

/**
 * The IMAP app password for a mailbox. It is stored as given, in plaintext, and is never returned
 * by any endpoint — {@link UserResponse} deliberately has no field for it.
 */
public record SetSecretRequest(@NotBlank String imapPassword) {
}
