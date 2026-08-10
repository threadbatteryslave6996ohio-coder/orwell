package dev.orwell.google.gmail.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * @param url the absolute {@code http}/{@code https} endpoint to POST each new mail to. Validated
 *            in {@link SubscriptionController} rather than by a pattern here, so that a bad URL is
 *            rejected for a stated reason instead of a regex mismatch.
 */
public record CreateSubscriptionRequest(
        @NotBlank @Size(max = 2048) String url
) {
}
