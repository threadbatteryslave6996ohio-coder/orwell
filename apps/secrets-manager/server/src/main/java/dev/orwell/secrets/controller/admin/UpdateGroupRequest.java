package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.Size;

public record UpdateGroupRequest(
        @Size(max = 255)
        String name,

        String description
) {
}
