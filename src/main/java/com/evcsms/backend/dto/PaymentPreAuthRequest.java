package com.evcsms.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentPreAuthRequest(
        @NotNull Long sessionId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount
) {
}
