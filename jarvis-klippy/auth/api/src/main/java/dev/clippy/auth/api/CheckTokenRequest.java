package dev.clippy.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckTokenRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotBlank
        String token
) {
}
