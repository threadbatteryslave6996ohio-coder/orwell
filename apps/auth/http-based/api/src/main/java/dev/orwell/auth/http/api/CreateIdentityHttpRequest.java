package dev.orwell.auth.http.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateIdentityHttpRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotBlank
        @Size(min = 8, max = 256)
        String secret
) {
}
