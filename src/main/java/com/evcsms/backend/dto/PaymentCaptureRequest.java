package com.evcsms.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentCaptureRequest(
        @NotBlank String preAuthId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount
) {
}
