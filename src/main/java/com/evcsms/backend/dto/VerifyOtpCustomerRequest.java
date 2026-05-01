package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record VerifyOtpCustomerRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$", message = "phoneNumber must be 10 digits")
        String phoneNumber,

        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$", message = "otp must be 6 digits")
        String otp,

        @NotNull
        SendOtpRequest.OtpPurpose purpose
) {
}
