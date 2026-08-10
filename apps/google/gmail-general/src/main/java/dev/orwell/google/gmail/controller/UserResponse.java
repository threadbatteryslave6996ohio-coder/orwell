package dev.orwell.google.gmail.controller;

import dev.orwell.google.gmail.entity.UserEntity;

import java.time.Instant;

/**
 * A user as returned over HTTP. Carries no secret: the IMAP password is write-only, so that a
 * caller able to create users cannot read back credentials it did not supply.
 */
public record UserResponse(Long id, String email, String clientId, Instant createdAt) {
    public static UserResponse from(UserEntity entity) {
        return new UserResponse(entity.getId(), entity.getEmail(), entity.getClientId(), entity.getCreatedAt());
    }
}
