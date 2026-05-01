package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$", message = "phoneNumber must be 10 digits")
        String phoneNumber,

        @NotBlank
        String name,

        @NotBlank
        String otpToken
) {
}
