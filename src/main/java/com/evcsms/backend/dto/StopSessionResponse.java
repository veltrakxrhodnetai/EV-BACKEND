package com.evcsms.backend.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record StopSessionResponse(
        UUID sessionId,
        BigDecimal units,
        BigDecimal energyCost,
        BigDecimal gst,
        BigDecimal total,
        String status
) {
}
