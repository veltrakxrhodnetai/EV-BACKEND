package com.evcsms.backend.dto;

public record PaymentWebhookResponse(
        String preAuthId,
        String eventType,
        String status
) {
}
