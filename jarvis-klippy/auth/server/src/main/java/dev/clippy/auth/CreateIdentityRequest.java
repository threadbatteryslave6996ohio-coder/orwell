package dev.clippy.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateIdentityRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotBlank
        @Size(min = 8, max = 256)
        String secret
) {
}
