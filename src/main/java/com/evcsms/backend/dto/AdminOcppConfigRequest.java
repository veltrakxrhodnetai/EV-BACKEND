package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminOcppConfigRequest(
        @NotBlank(message = "Charge point ID is required")
        String chargePointId,

        @NotBlank(message = "WebSocket URL is required")
        String websocketUrl,

        @NotNull(message = "Heartbeat interval is required")
        Integer heartbeatIntervalSec,

        @NotNull(message = "Meter value interval is required")
        Integer meterValueIntervalSec,

        String allowedIpsJson,

        @NotBlank(message = "Security mode is required")
        String securityMode,

        String tokenHash,
        String tlsProfileJson
) {
}
