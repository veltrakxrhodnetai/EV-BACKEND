package com.evcsms.backend.service;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.ChargingSession;
import com.evcsms.backend.model.MeterValue;
import com.evcsms.backend.model.Tariff;
import com.evcsms.backend.ocpp.OcppWebSocketHandler;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.MeterValueRepository;
import com.evcsms.backend.repository.TariffRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class ChargingSessionService {

    private static final Logger logger = LoggerFactory.getLogger(ChargingSessionService.class);
    private static final double GST_PERCENT = 18.0;
    private static final double DEFAULT_PLATFORM_FEE_PERCENT = 12.0;

    private final ChargingSessionRepository chargingSessionRepository;
    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;
    private final MeterValueRepository meterValueRepository;
    private final TariffRepository tariffRepository;
    private final OcppWebSocketHandler ocppWebSocketHandler;
    private final PaymentService paymentService;
    private final Msg91OtpService msg91OtpService;

    public ChargingSessionService(
            ChargingSessionRepository chargingSessionRepository,
            ChargerRepository chargerRepository,
            ConnectorRepository connectorRepository,
            MeterValueRepository meterValueRepository,
            TariffRepository tariffRepository,
            OcppWebSocketHandler ocppWebSocketHandler,
                PaymentService paymentService,
                Msg91OtpService msg91OtpService
    ) {
        this.chargingSessionRepository = chargingSessionRepository;
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
        this.meterValueRepository = meterValueRepository;
        this.tariffRepository = tariffRepository;
        this.ocppWebSocketHandler = ocppWebSocketHandler;
        this.paymentService = paymentService;
        this.msg91OtpService = msg91OtpService;
    }

    @Transactional(readOnly = true)
    public ChargingSession getSessionById(Long sessionId) {
        return chargingSessionRepository.findByIdWithChargerAndConnector(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
    }

    public void registerChargerBoot(String chargerId, JsonNode payload) {
        logger.debug("registerChargerBoot called for chargerId={}", chargerId);
    }

    public void authorizeIdTag(String chargerId, String idTag) {
        logger.debug("authorizeIdTag called for chargerId={}, idTag={}", chargerId, idTag);
    }

    @Transactional
    public void startTransaction(String chargerIdentity, JsonNode payload) {
        int connectorNo = payload.path("connectorId").asInt(1);
        long meterStart = payload.path("meterStart").asLong(0L);

        Charger charger = chargerRepository.findByOcppIdentity(chargerIdentity)
                .orElseThrow(() -> new IllegalArgumentException("Charger not found: " + chargerIdentity));

        chargingSessionRepository
                .findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(charger.getId(), connectorNo, "PENDING_START")
                .ifPresent(session -> {
                    int nextTransactionId = chargingSessionRepository.findMaxOcppTransactionId() + 1;
                    session.setOcppTransactionId(nextTransactionId);
                    session.setMeterStart(meterStart);
                    session.setStatus("ACTIVE");
                    session.setStartedAt(LocalDateTime.now());
                    chargingSessionRepository.save(session);
                });
    }

    @Transactional
    public void saveMeterValues(String chargerId, JsonNode payload) {
        int transactionId = payload.path("transactionId").asInt(-1);
        if (transactionId < 0) {
            return;
        }

        chargingSessionRepository.findByOcppTransactionId(transactionId).ifPresent(session -> {
            JsonNode sampledValues = payload.path("sampledValue");
            if (!sampledValues.isArray() && payload.path("meterValue").isArray() && payload.path("meterValue").size() > 0) {
                sampledValues = payload.path("meterValue").get(0).path("sampledValue");
            }

            long energyWh = extractSampledLong(sampledValues, "Energy.Active.Import.Register", "Wh");
            double powerW = extractSampledDouble(sampledValues, "Power.Active.Import", "W");
            double voltageV = extractSampledDouble(sampledValues, "Voltage", "V");
            double currentA = extractSampledDouble(sampledValues, "Current.Import", "A");

            MeterValue meterValue = new MeterValue();
            meterValue.setSession(session);
            meterValue.setTimestamp(LocalDateTime.now());
            meterValue.setEnergyWh(energyWh);
            meterValue.setPowerW(powerW);
            meterValue.setVoltageV(voltageV);
            meterValue.setCurrentA(currentA);
            meterValueRepository.save(meterValue);

            // Check if charging limit has been reached and auto-stop if needed
            if ("ACTIVE".equals(session.getStatus())) {
                checkAndAutoStop(session, energyWh);
            }
        });
    }

    /**
     * Check if the charging limit has been reached and automatically stop if it has
     */
    private void checkAndAutoStop(ChargingSession session, long currentMeterWh) {
        if (session.getLimitType() == null || session.getLimitValue() == null) {
            return;
        }

        boolean shouldStop = false;
        String limitType = session.getLimitType().toUpperCase();
        double limitValue = session.getLimitValue();

        switch (limitType) {
            case "ENERGY": {
                // Convert Wh to kWh
                long meterStart = session.getMeterStart() != null ? session.getMeterStart() : 0L;
                double energyConsumedKwh = (currentMeterWh - meterStart) / 1000.0;
                if (energyConsumedKwh >= limitValue) {
                    shouldStop = true;
                    logger.info("Session {} reached ENERGY limit: {}/{} kWh", 
                        session.getId(), energyConsumedKwh, limitValue);
                }
                break;
            }
            case "TIME": {
                // Convert minutes to actual elapsed time
                if (session.getStartedAt() != null) {
                    long elapsedMinutes = ChronoUnit.MINUTES.between(session.getStartedAt(), LocalDateTime.now());
                    if (elapsedMinutes >= limitValue) {
                        shouldStop = true;
                        logger.info("Session {} reached TIME limit: {}/{} minutes", 
                            session.getId(), elapsedMinutes, limitValue);
                    }
                }
                break;
            }
            case "AMOUNT": {
                // Compare against total payable bill (energy + GST only).
                Charger charger = session.getCharger();
                if (charger != null && charger.getStation() != null) {
                    tariffRepository.findByStation_Id(charger.getStation().getId()).ifPresent(tariff -> {
                        long meterStart = session.getMeterStart() != null ? session.getMeterStart() : 0L;
                        double energyConsumedKwh = (currentMeterWh - meterStart) / 1000.0;
                        double baseAmount = Math.max(0.0, energyConsumedKwh) * tariff.getPricePerKwh();
                        double gstPercent = tariff.getGstPercent() == null ? 0.0 : tariff.getGstPercent();
                        double currentTotal = baseAmount + (baseAmount * gstPercent / 100.0);

                        double effectiveBudget = limitValue;
                        if (session.getPreauthAmount() != null && session.getPreauthAmount() > 0) {
                            effectiveBudget = Math.min(effectiveBudget, session.getPreauthAmount());
                        }

                        if (currentTotal >= effectiveBudget) {
                            logger.info("Session {} reached AMOUNT limit (total): ₹{}/₹{}", 
                                session.getId(), currentTotal, effectiveBudget);
                            completeSessionImmediately(session, currentMeterWh, "LIMIT_AMOUNT");
                        }
                    });
                }
                break;
            }
        }

        if (shouldStop && !"AMOUNT".equals(limitType)) {
            completeSessionImmediately(session, currentMeterWh, "LIMIT_" + limitType);
        }
    }

    /**
     * Send OCPP RemoteStopTransaction command to stop charging
     */
    private void sendAutoStopCommand(ChargingSession session) {
        try {
            Charger charger = session.getCharger();
            if (charger != null && session.getOcppTransactionId() != null) {
                logger.info("Auto-stopping session {} due to limit reached", session.getId());
                boolean sent = ocppWebSocketHandler.sendRemoteStop(
                    charger.getOcppIdentity(), 
                    session.getOcppTransactionId()
                );
                if (sent) {
                    session.setStatus("STOPPING");
                    chargingSessionRepository.save(session);
                } else {
                    logger.warn("Auto-stop command not sent for session {} because charger {} is offline",
                            session.getId(), charger.getOcppIdentity());
                }
            }
        } catch (Exception ex) {
            logger.error("Failed to send auto-stop command for session {}: {}", 
                session.getId(), ex.getMessage(), ex);
        }
    }

    @Transactional
    public void completeSessionImmediately(Long sessionId, Long meterStopWh) {
        ChargingSession session = chargingSessionRepository.findByIdWithChargerAndConnector(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        completeSessionImmediately(session, meterStopWh == null ? null : meterStopWh.longValue(), "MANUAL_STOP");
    }

    private void completeSessionImmediately(ChargingSession session, Long meterStopWh, String reason) {
        if ("COMPLETED".equalsIgnoreCase(session.getStatus())) {
            return;
        }

        Charger charger = session.getCharger();
        long meterStart = session.getMeterStart() != null ? session.getMeterStart() : 0L;
        long meterStop = meterStopWh != null ? meterStopWh : meterValueRepository.findLatestBySessionId(session.getId())
                .map(MeterValue::getEnergyWh)
                .orElse(meterStart);

        // Ask charger to stop as best effort; billing/finalization is immediate.
        if (charger != null && session.getOcppTransactionId() != null) {
            ocppWebSocketHandler.sendRemoteStop(charger.getOcppIdentity(), session.getOcppTransactionId());
        }

        session.setMeterStop(meterStop);
        session.setStatus("COMPLETED");
        session.setEndedAt(LocalDateTime.now());
        stampSessionOwnership(session);

        if (charger != null && charger.getStation() != null) {
            tariffRepository.findByStation_Id(charger.getStation().getId()).ifPresent(tariff -> {
                calculateFinalBill(session, tariff);
                processPaymentSettlement(session);
                generateInvoice(session);
            });
        }

        chargingSessionRepository.save(session);

        msg91OtpService.sendChargeCompleteMessage(session.getPhoneNumber());

        if (charger != null) {
            connectorRepository.findByCharger_IdAndConnectorNo(charger.getId(), session.getConnectorNo())
                    .ifPresent(connector -> {
                        connector.setStatus("Available");
                        connectorRepository.save(connector);
                    });
            charger.setStatus("Available");
            chargerRepository.save(charger);
        }

        logger.info("Session {} completed immediately (reason={}) total=₹{}",
                session.getId(), reason, session.getTotalAmount());
    }

    @Transactional
    public void stopTransaction(String chargerId, JsonNode payload) {
        int transactionId = payload.path("transactionId").asInt(-1);
        long meterStop = payload.path("meterStop").asLong(0L);

        chargingSessionRepository.findByOcppTransactionId(transactionId).ifPresent(session -> {
            if ("COMPLETED".equalsIgnoreCase(session.getStatus())) {
                logger.info("Ignoring StopTransaction for already completed session {}", session.getId());
                return;
            }
            session.setMeterStop(meterStop);
            session.setStatus("COMPLETED");
            session.setEndedAt(LocalDateTime.now());
            stampSessionOwnership(session);

            // Calculate final billing
            Charger charger = session.getCharger();
            if (charger != null && charger.getStation() != null) {
                tariffRepository.findByStation_Id(charger.getStation().getId()).ifPresent(tariff -> {
                    calculateFinalBill(session, tariff);
                    
                    // Handle payment capture and refund
                    processPaymentSettlement(session);
                    
                    // Generate invoice
                    generateInvoice(session);
                });
            }

            chargingSessionRepository.save(session);

            msg91OtpService.sendChargeCompleteMessage(session.getPhoneNumber());

            if (charger != null) {
                connectorRepository.findByCharger_IdAndConnectorNo(charger.getId(), session.getConnectorNo())
                        .ifPresent(connector -> {
                            connector.setStatus("Available");
                            connectorRepository.save(connector);
                        });
                charger.setStatus("Available");
                chargerRepository.save(charger);
            }

            logger.info("Session {} completed. Total amount: ₹{}", 
                session.getId(), session.getTotalAmount());
        });
    }

    /**
     * Calculate final bill including base amount, GST, and session fee
     */
    private void calculateFinalBill(ChargingSession session, Tariff tariff) {
        stampSessionOwnership(session);

        long meterStart = session.getMeterStart() != null ? session.getMeterStart() : 0L;
        long meterStop = session.getMeterStop() != null ? session.getMeterStop() : 0L;
        
        double energyConsumedKwh = Math.max(0, (meterStop - meterStart) / 1000.0);
        session.setEnergyConsumedKwh(energyConsumedKwh);
        
        double energyAmount = energyConsumedKwh * tariff.getPricePerKwh();
        double uncappedTotal = roundCurrency(energyAmount * (1.0 + GST_PERCENT / 100.0));
        double effectiveCap = resolveEffectiveAmountCap(session);
        double totalAmount = roundCurrency(Math.min(uncappedTotal, effectiveCap));

        double gstAmount = roundCurrency(totalAmount * GST_PERCENT / (100.0 + GST_PERCENT));
        double baseAmount = roundCurrency(totalAmount - gstAmount);
        double platformFeePercent = resolvePlatformFeePercent(tariff);
        double platformFee = roundCurrency(baseAmount * (platformFeePercent / 100.0));
        double ownerRevenue = roundCurrency(baseAmount - platformFee);

        session.setTotalAmount(totalAmount);
        session.setGstAmount(gstAmount);
        session.setBaseAmount(baseAmount);
        session.setPlatformFee(platformFee);
        session.setOwnerRevenue(ownerRevenue);
    }

    private double resolveEffectiveAmountCap(ChargingSession session) {
        double cap = Double.MAX_VALUE;

        if ("AMOUNT".equalsIgnoreCase(session.getLimitType()) && session.getLimitValue() != null) {
            cap = Math.min(cap, session.getLimitValue());
        }

        if (session.getPreauthAmount() != null && session.getPreauthAmount() > 0.0) {
            cap = Math.min(cap, session.getPreauthAmount());
        }

        return Math.max(0.0, cap);
    }

    private void stampSessionOwnership(ChargingSession session) {
        Charger charger = session.getCharger();
        if (charger == null) {
            return;
        }

        session.setChargerId(charger.getId());
        session.setStationId(charger.getStationId());
        session.setOwnerId(charger.getOwnerId());
    }

    private double roundCurrency(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private double resolvePlatformFeePercent(Tariff tariff) {
        if (tariff == null || tariff.getPlatformFeePercent() == null) {
            return DEFAULT_PLATFORM_FEE_PERCENT;
        }
        return Math.max(0.0, tariff.getPlatformFeePercent());
    }

    /**
     * Capture actual amount charged and refund unused pre-authorized amount
     */
    private void processPaymentSettlement(ChargingSession session) {
        String preauthId = session.getPreauthId();
        Double preauthAmount = session.getPreauthAmount();
        Double totalAmount = session.getTotalAmount();
        
        if (preauthId == null || preauthAmount == null || totalAmount == null) {
            logger.warn("Session {} missing payment details for settlement", session.getId());
            return;
        }

        try {
            // Capture the actual amount charged
            paymentService.capture(preauthId, BigDecimal.valueOf(totalAmount));
            session.setPaymentStatus("CAPTURED");
            
            // Calculate and refund unused amount
            double unusedAmount = Math.max(0, preauthAmount - totalAmount);
            if (unusedAmount > 0.01) { // Only refund if more than 1 paisa
                PaymentService.RefundResult refundResult = paymentService.refund(
                    preauthId, 
                    BigDecimal.valueOf(unusedAmount)
                );
                
                if (refundResult.success()) {
                    session.setRefundAmount(unusedAmount);
                    session.setRefundId(refundResult.refundId());
                    logger.info("Session {} refunded ₹{} (unused amount)", 
                        session.getId(), unusedAmount);
                }
            }
        } catch (Exception ex) {
            logger.error("Payment settlement failed for session {}: {}", 
                session.getId(), ex.getMessage(), ex);
            session.setPaymentStatus("SETTLEMENT_FAILED");
        }
    }

    /**
     * Generate invoice number and URL for receipt
     */
    private void generateInvoice(ChargingSession session) {
        try {
            // Generate invoice number: INV-YYYYMM-NNNNNN
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMM");
            String month = session.getEndedAt().format(formatter);
            String invoiceNumber = String.format("INV-%s-%06d", month, session.getId());
            
            // In production, this would be a real URL to download PDF invoice
            String invoiceUrl = String.format("/api/sessions/%d/invoice", session.getId());
            
            session.setInvoiceNumber(invoiceNumber);
            session.setInvoiceUrl(invoiceUrl);
            
            logger.info("Invoice generated for session {}: {}", session.getId(), invoiceNumber);
        } catch (Exception ex) {
            logger.error("Failed to generate invoice for session {}: {}", 
                session.getId(), ex.getMessage(), ex);
        }
    }

    private long extractSampledLong(JsonNode sampledValues, String measurand, String unit) {
        if (!sampledValues.isArray()) {
            return 0L;
        }
        for (JsonNode sampledValue : sampledValues) {
            String currentMeasurand = sampledValue.path("measurand").asText("");
            String currentUnit = sampledValue.path("unit").asText("");
            if (measurand.equalsIgnoreCase(currentMeasurand)
                    && (unit.equalsIgnoreCase(currentUnit) || currentUnit.isBlank())) {
                try {
                    return Long.parseLong(sampledValue.path("value").asText("0"));
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    private double extractSampledDouble(JsonNode sampledValues, String measurand, String unit) {
        if (!sampledValues.isArray()) {
            return 0.0;
        }
        for (JsonNode sampledValue : sampledValues) {
            String currentMeasurand = sampledValue.path("measurand").asText("");
            String currentUnit = sampledValue.path("unit").asText("");
            if (measurand.equalsIgnoreCase(currentMeasurand)
                    && (unit.equalsIgnoreCase(currentUnit) || currentUnit.isBlank())) {
                try {
                    return Double.parseDouble(sampledValue.path("value").asText("0"));
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }
        }
        return 0.0;
    }

    /**
     * Verify connector and wait for payment step before starting charging.
     */
    @Transactional
    public void verifyConnectorAwaitingPayment(long sessionId) {
        // Load session with minimal overhead
        ChargingSession session = chargingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));
        
        logger.info("verifyConnectorAwaitingPayment called for sessionId={}, status={}, connectorVerified={}",
                sessionId, session.getStatus(), session.getConnectorVerified());

        String status = session.getStatus();
        boolean verified = Boolean.TRUE.equals(session.getConnectorVerified());

        // Idempotent success for sessions that are already verified or have progressed beyond verification step.
        if (verified && java.util.Set.of("PENDING_PAYMENT", "PENDING_START", "ACTIVE", "STOPPING", "COMPLETED").contains(status)) {
            logger.info("Session {} already passed verification step (status={})", sessionId, status);
            return;
        }

        // Handle rare race where status is awaiting payment but connectorVerified flag is not yet set.
        if ("PENDING_PAYMENT".equals(status) && !verified) {
            session.setConnectorVerified(true);
            session.setConnectorVerifiedAt(LocalDateTime.now());
            chargingSessionRepository.save(session);
            logger.info("Session {} marked connectorVerified=true in PENDING_PAYMENT race recovery", sessionId);
            return;
        }
        
        // Validate session is in correct state
        if (!"PENDING_VERIFICATION".equals(status)) {
            throw new IllegalStateException("Session is not awaiting verification. Current status: " + status);
        }

        if (verified) {
            logger.warn("Connector already verified for session {}", sessionId);
            return;
        }

        // Get primitive fields from session (no proxy access)
        Integer connectorNo = session.getConnectorNo();
        String startedBy = session.getStartedBy();
        
        // Get charger OCPP ID using direct JPQL query (no proxy)
        String chargerOcppId = chargingSessionRepository.findChargerOcppIdentityBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Charger not found for session: " + sessionId));

        if (!ocppWebSocketHandler.isChargerConnected(chargerOcppId)) {
            throw new IllegalStateException("Charger is offline. Please connect simulator/charger first.");
        }

        // Physical-plug validation: do not move to payment until connector indicates plugged state.
        String connectorStatus = getConnectorStatusForSession(sessionId);
        if (!isConnectorPluggedStatus(connectorStatus)) {
            throw new IllegalStateException(
                "Connector is not plugged into vehicle yet (current status: " + connectorStatus + "). " +
                "Please plug in and wait for charger status Preparing/Charging before continuing."
            );
        }
        
        logger.debug("Session {}: charger OCPP ID={}, connectorNo={}, startedBy={}", 
                sessionId, chargerOcppId, connectorNo, startedBy);
        
        // Mark connector as verified; payment is next step
        session.setConnectorVerified(true);
        session.setConnectorVerifiedAt(LocalDateTime.now());
        session.setStatus("PENDING_PAYMENT");
        session.setPaymentStatus("AWAITING_PAYMENT");
        chargingSessionRepository.save(session);
        logger.debug("Session {} marked as PENDING_PAYMENT", sessionId);
    }

    /**
     * Accept payment and only then trigger OCPP RemoteStartTransaction.
     */
    @Transactional
    public void acceptPaymentAndStartCharging(long sessionId) {
        ChargingSession session = chargingSessionRepository.findById(sessionId)
                .orElseThrow(() -> new RuntimeException("Session not found: " + sessionId));

        logger.info("acceptPaymentAndStartCharging called for sessionId={}, status={}, paymentStatus={}",
                sessionId, session.getStatus(), session.getPaymentStatus());

        if (!"PENDING_PAYMENT".equals(session.getStatus())) {
            throw new IllegalStateException("Session is not awaiting payment. Current status: " + session.getStatus());
        }

        if (!Boolean.TRUE.equals(session.getConnectorVerified())) {
            throw new IllegalStateException("Connector must be verified before payment");
        }

        Integer connectorNo = session.getConnectorNo();
        String startedBy = session.getStartedBy();

        String chargerOcppId = chargingSessionRepository.findChargerOcppIdentityBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Charger not found for session: " + sessionId));

        if (!ocppWebSocketHandler.isChargerConnected(chargerOcppId)) {
            throw new IllegalStateException("Charger is offline. Please connect simulator/charger first.");
        }

        // Re-check plug state right before payment and RemoteStart to prevent locked PENDING_START sessions.
        String connectorStatus = getConnectorStatusForSession(sessionId);
        if (!isConnectorPluggedStatus(connectorStatus)) {
            throw new IllegalStateException(
                "Connector unplugged before start (current status: " + connectorStatus + "). " +
                "Please plug in vehicle and verify connector again."
            );
        }

        Double preauthAmount = session.getPreauthAmount() == null ? 0.0 : session.getPreauthAmount();
        boolean ownerDeferredPayment = "OWNER".equalsIgnoreCase(session.getStartedBy());
        if (ownerDeferredPayment) {
            // Owner mode: start charging now, collect payment after session completion.
            session.setPaymentStatus("PAYMENT_PENDING");
            logger.info("Session {} started by owner; skipping pre-authorization", sessionId);
        } else if (session.getPreauthId() != null && "PREAUTH_SUCCESS".equalsIgnoreCase(session.getPaymentStatus())) {
            // Retry path: keep using the existing hold instead of creating a new pre-auth.
            logger.info("Session {} reusing existing pre-authorization {} for retry", sessionId, session.getPreauthId());
        } else {
            try {
                PaymentService.PreAuthResult preAuthResult = paymentService.createPreAuth(
                        session.getId(),
                        BigDecimal.valueOf(preauthAmount)
                );
                session.setPreauthId(preAuthResult.preAuthId());
                session.setPaymentStatus("PREAUTH_SUCCESS");
            } catch (Exception ex) {
                session.setPaymentStatus("PREAUTH_FAILED");
                chargingSessionRepository.save(session);
                throw new RuntimeException("Payment pre-authorization failed: " + ex.getMessage(), ex);
            }
        }

        session.setStatus("PENDING_START");
        chargingSessionRepository.save(session);
        logger.debug("Session {} marked as PENDING_START after payment", sessionId);

        // Send OCPP command after payment success
        try {
            logger.info("Sending RemoteStartTransaction for sessionId={}, charger={}, connectorNo={}",
                    sessionId, chargerOcppId, connectorNo);
            boolean remoteStartAccepted = ocppWebSocketHandler.sendRemoteStart(chargerOcppId, connectorNo, startedBy);
            if (!remoteStartAccepted) {
                logger.warn("RemoteStartTransaction was not accepted for sessionId={} charger={} connectorNo={}",
                    sessionId, chargerOcppId, connectorNo);
                session.setStatus("PENDING_PAYMENT");
                session.setEndedAt(null);
                chargingSessionRepository.save(session);
                throw new IllegalStateException("Charger rejected start request. Payment hold is still valid; please try start again.");
            }
            
            // Update connector status to Preparing - using direct query to avoid proxy
            Long connectorId = chargingSessionRepository.findConnectorIdBySessionId(sessionId)
                    .orElseThrow(() -> new RuntimeException("Connector not found for session: " + sessionId));
            connectorRepository.updateStatusById(connectorId, "Preparing");
            
            logger.info("RemoteStartTransaction sent successfully for session {}", sessionId);
        } catch (Exception ex) {
            logger.error("Failed to send RemoteStartTransaction for sessionId={}", sessionId, ex);
            session.setStatus("PENDING_PAYMENT");
            session.setEndedAt(null);
            chargingSessionRepository.save(session);
            throw new RuntimeException("Failed to initiate charging: " + ex.getMessage(), ex);
        }
    }

    private String getConnectorStatusForSession(long sessionId) {
        Long connectorId = chargingSessionRepository.findConnectorIdBySessionId(sessionId)
                .orElseThrow(() -> new RuntimeException("Connector not found for session: " + sessionId));

        return connectorRepository.findById(connectorId)
                .map(connector -> connector.getStatus() == null ? "UNKNOWN" : connector.getStatus())
                .orElse("UNKNOWN");
    }

    private boolean isConnectorPluggedStatus(String status) {
        if (status == null) {
            return false;
        }

        String normalized = status.trim().toUpperCase(Locale.ROOT);
        return "PREPARING".equals(normalized)
                || "CHARGING".equals(normalized)
                || "SUSPENDEDEV".equals(normalized)
                || "SUSPENDEDEVSE".equals(normalized)
                || "FINISHING".equals(normalized);
    }
}
