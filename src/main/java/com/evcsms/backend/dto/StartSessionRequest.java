package com.evcsms.backend.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record StartSessionRequest(
        @NotNull @Positive Long chargerId,
        @NotNull @Positive Long connectorId,
        @NotNull @Positive Integer connectorNo,
        String vehicleNumber,
        @NotBlank String phoneNumber,
        @NotBlank String startedBy,
        @NotBlank String limitType,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) Double limitValue,
        @NotBlank String paymentMode
) {
}
