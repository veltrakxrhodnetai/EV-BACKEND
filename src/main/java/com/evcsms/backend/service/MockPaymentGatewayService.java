package com.evcsms.backend.service;

import com.evcsms.backend.dto.MockGatewayResponse;
import com.evcsms.backend.util.IdUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MockPaymentGatewayService {

    private final Map<String, PreAuthHold> holds = new ConcurrentHashMap<>();

    public MockGatewayResponse preAuth(UUID sessionId, BigDecimal amount, String currency) {
        String resolvedCurrency = (currency == null || currency.isBlank()) ? "INR" : currency;
        String preAuthId = IdUtils.generatePrefixedId("preauth");
        String providerReference = IdUtils.generatePrefixedId("mock-preauth");

        holds.put(preAuthId, new PreAuthHold(sessionId, amount, resolvedCurrency, "AUTHORIZED"));

        return new MockGatewayResponse(
                preAuthId,
                "AUTHORIZED",
                providerReference,
                amount,
                resolvedCurrency,
                true,
                Instant.now()
        );
    }

    public MockGatewayResponse capture(String preAuthId, BigDecimal amount) {
        PreAuthHold hold = getHold(preAuthId);
        hold.setStatus("CAPTURED");

        return new MockGatewayResponse(
                preAuthId,
                "CAPTURED",
            IdUtils.generatePrefixedId("mock-capture"),
                amount,
                hold.getCurrency(),
                true,
                Instant.now()
        );
    }

    public MockGatewayResponse release(String preAuthId, BigDecimal amount) {
        PreAuthHold hold = getHold(preAuthId);
        hold.setStatus("RELEASED");

        return new MockGatewayResponse(
                preAuthId,
                "RELEASED",
            IdUtils.generatePrefixedId("mock-release"),
                amount,
                hold.getCurrency(),
                true,
                Instant.now()
        );
    }

    private PreAuthHold getHold(String preAuthId) {
        PreAuthHold hold = holds.get(preAuthId);
        if (hold == null) {
            throw new IllegalArgumentException("Unknown preAuthId: " + preAuthId);
        }
        return hold;
    }

    private static class PreAuthHold {
        private final UUID sessionId;
        private final BigDecimal amount;
        private final String currency;
        private String status;

        private PreAuthHold(UUID sessionId, BigDecimal amount, String currency, String status) {
            this.sessionId = sessionId;
            this.amount = amount;
            this.currency = currency;
            this.status = status;
        }

        public UUID getSessionId() {
            return sessionId;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public String getCurrency() {
            return currency;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }
    }
}
