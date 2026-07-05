package dev.orwell.secrets.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record Bundle(
        long id,
        String name,
        String description,
        Instant createdAt
) {
}
