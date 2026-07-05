package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.Size;

public record UpdateEnvironmentRequest(
        @Size(max = 255)
        String name,

        String value
) {
}
