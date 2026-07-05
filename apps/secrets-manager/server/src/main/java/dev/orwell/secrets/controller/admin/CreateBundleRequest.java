package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateBundleRequest(
        @NotBlank
        @Size(max = 255)
        String name,

        String description,

        List<Long> envIds
) {
}
