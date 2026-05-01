package com.evcsms.backend.model;

import java.math.BigDecimal;

public record BillingResult(
        BigDecimal units,
        BigDecimal energyCost,
        BigDecimal gst,
        BigDecimal total
) {
}