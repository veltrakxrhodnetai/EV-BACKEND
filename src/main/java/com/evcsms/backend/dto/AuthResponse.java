package com.evcsms.backend.dto;

public record AuthResponse(
        String token,
        String tokenType,
        long expiresInSeconds,
        boolean hasPasscode,
        String name
) {
}
