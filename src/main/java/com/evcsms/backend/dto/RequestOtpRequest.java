package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record RequestOtpRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10,15}$", message = "mobile must be 10-15 digits")
        String mobile
) {
}
