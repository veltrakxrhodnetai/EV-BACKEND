package com.evcsms.backend.dto;

public record CheckPhoneResponse(
        boolean exists,
        boolean hasPasscode
) {
}
