package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminConnectorRequest(
        @NotNull(message = "Charger ID is required")
        Long chargerId,

        @NotNull(message = "Connector number is required")
        Integer connectorNo,

        @NotBlank(message = "Connector type is required")
        String type,

        @NotNull(message = "Max power is required")
        Double maxPowerKw
) {
}
