package com.evcsms.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record StartSessionResponse(
        UUID sessionId,
        BigDecimal preAuthAmount,
        String preAuthId
) {
}
