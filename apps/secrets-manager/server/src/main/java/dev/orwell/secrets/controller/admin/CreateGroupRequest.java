package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateGroupRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        String description
) {
}
