package com.evcsms.backend.service;

import com.evcsms.backend.model.ChargingSession;
import com.evcsms.backend.model.MeterValue;
import com.evcsms.backend.model.PaymentRecord;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.MeterValueRepository;
import com.evcsms.backend.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChargingSessionServiceTest {

    @Mock
    private ChargingSessionRepository chargingSessionRepository;

    @Mock
    private MeterValueRepository meterValueRepository;

    @Mock
    private PaymentRepository paymentRepository;

    @Captor
    private ArgumentCaptor<ChargingSession> chargingSessionCaptor;

    @Captor
    private ArgumentCaptor<PaymentRecord> paymentRecordCaptor;

    @Captor
    private ArgumentCaptor<MeterValue> meterValueCaptor;

    private ChargingSessionService chargingSessionService;

    @BeforeEach
    void setUp() {
        chargingSessionService = new ChargingSessionService(
                chargingSessionRepository,
                meterValueRepository,
                paymentRepository
        );
    }

    @Test
    void startSessionFromApi_persistsNullMeterStart() {
        String chargerId = "CHARGER-01";
        Integer connectorId = 1;
        String userId = UUID.randomUUID().toString();

        when(chargingSessionRepository.save(any(ChargingSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        chargingSessionService.createSessionFromApi(
                chargerId,
                connectorId,
                userId,
                "API",
                "ENERGY",
                new BigDecimal("10.0")
        );

        verify(chargingSessionRepository, times(1)).save(chargingSessionCaptor.capture());
        ChargingSession savedSession = chargingSessionCaptor.getValue();

        assertEquals(chargerId, savedSession.getChargerId());
        assertEquals(connectorId, savedSession.getConnectorNumber());
        assertNull(savedSession.getMeterStart());
    }

    @Test
    void stopSession_computesFinalBillAndStoresPaymentAmount() {
        UUID sessionId = UUID.randomUUID();
        Instant stopTime = Instant.parse("2026-01-01T10:15:30Z");

        ChargingSession existingSession = ChargingSession.builder()
                .id(sessionId)
                .chargerId("CHARGER-02")
                .connectorNumber(2)
                .status("ACTIVE")
                .meterStart(new BigDecimal("1000"))
                .startTime(Instant.parse("2026-01-01T10:00:00Z"))
                .transactionId("tx-123")
                .build();

        when(chargingSessionRepository.findById(sessionId)).thenReturn(Optional.of(existingSession));
        when(chargingSessionRepository.save(any(ChargingSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        BigDecimal meterStop = new BigDecimal("3500");
        chargingSessionService.stopSession(sessionId, meterStop, stopTime, "Remote");

        verify(paymentRepository, times(1)).save(paymentRecordCaptor.capture());
        PaymentRecord paymentRecord = paymentRecordCaptor.getValue();

        assertEquals(sessionId, paymentRecord.getSessionId());
        assertEquals(new BigDecimal("36.88"), paymentRecord.getAmount());
        assertEquals("BILLING", paymentRecord.getOperation());

        verify(chargingSessionRepository, times(1)).save(chargingSessionCaptor.capture());
        ChargingSession updatedSession = chargingSessionCaptor.getValue();
        assertEquals("ENDED", updatedSession.getStatus());
        assertEquals(meterStop, updatedSession.getMeterStop());

        verify(meterValueRepository, times(1)).save(meterValueCaptor.capture());
        MeterValue finalMeter = meterValueCaptor.getValue();
        assertEquals(sessionId, finalMeter.getSessionId());
        assertEquals(new BigDecimal("3.500"), finalMeter.getEnergyKwh());
    }
}
