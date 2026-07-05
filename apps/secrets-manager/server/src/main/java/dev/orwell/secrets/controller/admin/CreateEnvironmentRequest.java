package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateEnvironmentRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        @NotBlank
        String value
) {
}
