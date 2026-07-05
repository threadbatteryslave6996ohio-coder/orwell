package dev.orwell.secrets.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleDetail(
        long id,
        String name,
        String description,
        Instant createdAt,
        List<Environment> environments
) {
}
