package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateAdminRequest(
        @NotBlank
        @Size(max = 128)
        String name
) {
}
