package dev.orwell.google.gmail.controller;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param email    the mailbox address, also the IMAP login the poller connects with
 * @param clientId the auth-server client id of the consumer allowed to read this mailbox
 */
public record CreateUserRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(max = 255) String clientId
) {
}
