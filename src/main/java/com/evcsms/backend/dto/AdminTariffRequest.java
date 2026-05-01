package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public record AdminTariffRequest(
        @NotBlank(message = "Scope type is required (STATION or CHARGER)")
        String scopeType,

        @NotNull(message = "Station ID or Charger ID is required")
        Long scopeId,

        @NotNull(message = "Price per kWh is required")
        Double pricePerKwh,

        @NotNull(message = "GST percent is required")
        Double gstPercent,

        Double sessionFee,
        Double idleFee,
        Double timeFee,

        @NotBlank(message = "Currency is required")
        String currency,

        Instant effectiveFrom,
        Instant effectiveTo
) {
}
