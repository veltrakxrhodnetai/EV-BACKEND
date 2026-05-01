package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CheckPhoneRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$", message = "phoneNumber must be 10 digits")
        String phoneNumber
) {
}
