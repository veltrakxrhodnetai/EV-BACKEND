package com.evcsms.backend.dto;

import com.evcsms.backend.model.SessionStartedBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        String chargerId,
        Integer connectorNumber,
        SessionStartedBy startedBy,
        UUID userId,
        UUID ownerId,
        String vehicleNumber,
        String limitType,
        BigDecimal limitValue,
        BigDecimal meterStart,
        BigDecimal meterStop,
        String status,
        Instant startTime,
        Instant endTime
) {
}
