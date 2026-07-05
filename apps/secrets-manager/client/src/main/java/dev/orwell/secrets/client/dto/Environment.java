package dev.orwell.secrets.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Environment(
        long id,
        String name,
        String value,
        Instant createdAt,
        Instant updatedAt
) {
}
