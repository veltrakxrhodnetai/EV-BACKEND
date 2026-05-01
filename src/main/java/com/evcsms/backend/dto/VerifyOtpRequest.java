package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10,15}$", message = "mobile must be 10-15 digits")
        String mobile,

        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$", message = "otp must be 6 digits")
        String otp
) {
}
