package com.evcsms.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MockGatewayResponse(
        String preAuthId,
        String status,
        String providerReference,
        BigDecimal amount,
        String currency,
        boolean mock,
        Instant timestamp
) {
}
