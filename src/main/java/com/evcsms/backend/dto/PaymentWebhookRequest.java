package com.evcsms.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record PaymentWebhookRequest(
        @NotBlank String eventType,
        @NotBlank String paymentStatus,
        @NotBlank String preAuthId,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal amount,
        String payload
) {
}
