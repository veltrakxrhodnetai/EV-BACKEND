package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record AdminRfidRequest(
        @NotBlank(message = "RFID UID is required")
        String uid,

        Long linkedUserId,
        Long fleetId,

        @NotBlank(message = "Status is required")
        String status,

        String blockedReason
) {
}
