package dev.orwell.auth.http.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CheckTokenHttpRequest(
        @NotBlank
        @Size(max = 128)
        String clientId,

        @NotBlank
        String token
) {
}
