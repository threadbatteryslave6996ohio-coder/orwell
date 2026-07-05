package dev.orwell.secrets.controller.admin;

import jakarta.validation.constraints.NotNull;

import java.util.List;

public record SetBundleEnvsRequest(
        @NotNull
        List<Long> envIds
) {
}
