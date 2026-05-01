package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record SetPasscodeRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$", message = "phoneNumber must be 10 digits")
        String phoneNumber,

        @NotBlank
        @Pattern(regexp = "^[0-9]{4}$", message = "passcode must be 4 digits")
        String passcode,

        String otpToken  // Optional: present when resetting passcode
) {
}
