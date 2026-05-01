package com.evcsms.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record MockPreAuthRequest(
        @NotNull UUID sessionId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount,
        String currency
) {
}
