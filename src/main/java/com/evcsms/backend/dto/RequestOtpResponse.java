package com.evcsms.backend.dto;

public record RequestOtpResponse(
        String message,
        long expiresInSeconds
) {
}
