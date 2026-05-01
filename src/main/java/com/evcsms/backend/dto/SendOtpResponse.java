package com.evcsms.backend.dto;

public record SendOtpResponse(
        String message,
        long expiresInSeconds
) {
}
