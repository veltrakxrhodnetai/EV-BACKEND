package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record SendOtpRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$", message = "phoneNumber must be 10 digits")
        String phoneNumber,

        @NotNull
        OtpPurpose purpose
) {
    public enum OtpPurpose {
        LOGIN,
        REGISTER,
        RESET_PASSCODE
    }
}
