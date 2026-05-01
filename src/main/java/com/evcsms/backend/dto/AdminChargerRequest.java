package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminChargerRequest(
        @NotNull(message = "Station ID is required")
        Long stationId,

        @NotBlank(message = "OCPP identity is required")
        String ocppIdentity,

        @NotBlank(message = "Charger name is required")
        String name,

        String vendorName,
        String model,
        String serialNumber,

        @NotBlank(message = "Charger type is required (AC/DC)")
        String chargerType,

        @NotNull(message = "Max power is required")
        Double maxPowerKw,

        String ocppVersion,
        Boolean enabled
) {
}
