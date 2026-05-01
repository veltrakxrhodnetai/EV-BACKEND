package com.evcsms.backend.controller;

import com.evcsms.backend.aspect.OwnerStationAccessAspect;
import com.evcsms.backend.audit.Audit;
import com.evcsms.backend.dto.StartSessionRequest;
import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.ChargingSession;
import com.evcsms.backend.model.CompletedChargingLog;
import com.evcsms.backend.model.Connector;
import com.evcsms.backend.model.MeterValue;
import com.evcsms.backend.model.Tariff;
import com.evcsms.backend.ocpp.OcppWebSocketHandler;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.CompletedChargingLogRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.MeterValueRepository;
import com.evcsms.backend.repository.TariffRepository;
import com.evcsms.backend.service.ChargingSessionService;
import com.evcsms.backend.service.OwnerAuthService;
import com.evcsms.backend.service.PaymentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "*")
public class SessionController {

    private static final Logger logger = LoggerFactory.getLogger(SessionController.class);
    private final ConcurrentHashMap<Long, Long> sessionMaxMeterCache = new ConcurrentHashMap<>();

    private final ChargingSessionService chargingSessionService;
    private final ChargingSessionRepository chargingSessionRepository;
    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;
    private final TariffRepository tariffRepository;
    private final MeterValueRepository meterValueRepository;
    private final CompletedChargingLogRepository completedChargingLogRepository;
    private final OcppWebSocketHandler ocppWebSocketHandler;
    private final OwnerStationAccessAspect ownerStationAccessAspect;
    private final PaymentService paymentService;

    public SessionController(
        ChargingSessionService chargingSessionService,
        ChargingSessionRepository chargingSessionRepository,
        ChargerRepository chargerRepository,
        ConnectorRepository connectorRepository,
        TariffRepository tariffRepository,
        MeterValueRepository meterValueRepository,
        CompletedChargingLogRepository completedChargingLogRepository,
        OcppWebSocketHandler ocppWebSocketHandler,
        OwnerStationAccessAspect ownerStationAccessAspect,
        PaymentService paymentService
    ) {
        this.chargingSessionService = chargingSessionService;
        this.chargingSessionRepository = chargingSessionRepository;
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
        this.tariffRepository = tariffRepository;
        this.meterValueRepository = meterValueRepository;
        this.completedChargingLogRepository = completedChargingLogRepository;
        this.ocppWebSocketHandler = ocppWebSocketHandler;
        this.ownerStationAccessAspect = ownerStationAccessAspect;
        this.paymentService = paymentService;
    }

    @PostMapping("/start")
    @Audit
    public ResponseEntity<?> startSession(@Valid @RequestBody StartSessionRequest request, HttpServletRequest httpRequest) {
        Charger charger = chargerRepository.findById(request.chargerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found: " + request.chargerId()));
        
        // Validate owner access to station (if authorization header present)
        try {
            String authHeader = httpRequest.getHeader("Authorization");
            ownerStationAccessAspect.validateOwnerStationAccess(charger.getStation().getId(), authHeader);
        } catch (IllegalAccessException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
        }

        Connector connector = connectorRepository.findById(request.connectorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found: " + request.connectorId()));

        if (!connector.getCharger().getId().equals(charger.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connector does not match charger");
        }

        Integer resolvedConnectorNo = connector.getConnectorNo();
        if (!resolvedConnectorNo.equals(request.connectorNo())) {
            logger.warn("Connector number mismatch in start request: request connectorNo={}, resolved connectorNo={} for connectorId={}",
                    request.connectorNo(), resolvedConnectorNo, request.connectorId());
        }

        cleanupStaleConnectorSessions(charger.getId(), resolvedConnectorNo);

        // Always check for an active session flow regardless of connector status
        boolean activeFlowExists = chargingSessionRepository.existsByCharger_IdAndConnectorNoAndStatusIn(
                charger.getId(),
            resolvedConnectorNo,
                java.util.List.of("PENDING_VERIFICATION", "PENDING_START", "ACTIVE", "STOPPING")
        );

        if (activeFlowExists) {
            logger.warn("Rejecting duplicate session: active flow exists for chargerId={}, connectorNo={}",
                    charger.getId(), resolvedConnectorNo);
            return ResponseEntity.badRequest().body(Map.of("error", "A charging session is already in progress on this connector. Please wait or cancel the existing session."));
        }

        if ("Charging".equalsIgnoreCase(connector.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Connector is currently charging. Please use an available connector."
            ));
        }

        // Get tariff for pre-auth amount calculation
        Tariff tariff = tariffRepository.findByStation_Id(charger.getStation().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tariff not found for station"));

        // Calculate pre-authorization amount based on limit type
        Double preauthAmount = calculatePreauthAmount(request.limitType(), request.limitValue(), tariff);

        // Create charging session with PENDING_VERIFICATION status (waiting for connector to be plugged in)
        ChargingSession session = new ChargingSession();
        session.setCharger(charger);
        session.setConnector(connector);
        session.setConnectorNo(resolvedConnectorNo);
        String normalizedVehicleNumber = request.vehicleNumber() == null ? null : request.vehicleNumber().trim();
        if (normalizedVehicleNumber != null && normalizedVehicleNumber.isEmpty()) {
            normalizedVehicleNumber = null;
        }
        session.setVehicleNumber(normalizedVehicleNumber);
        session.setPhoneNumber(request.phoneNumber().trim());
        session.setStartedBy(request.startedBy().toUpperCase());
        session.setLimitType(request.limitType().toUpperCase());
        session.setLimitValue(request.limitValue());
        session.setPaymentMode(request.paymentMode().toUpperCase());
        session.setPaymentStatus("NOT_INITIATED");
        session.setStatus("PENDING_VERIFICATION");
        session.setStartedAt(LocalDateTime.now());
        session.setPreauthAmount(preauthAmount);
        session.setConnectorVerified(false);

        ChargingSession saved = chargingSessionRepository.save(session);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", saved.getId());
        response.put("preauthAmount", preauthAmount);
        response.put("message", "Session created. Please plug and verify connector.");
        response.put("status", "PENDING_VERIFICATION");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Calculate pre-authorization amount based on limit type
     * - AMOUNT: Direct rupee amount
     * - ENERGY: kWh * price per kWh + 20% buffer
     * - TIME: Estimated kWh (assume 22kW avg) * price + 20% buffer
     */
    private Double calculatePreauthAmount(String limitType, Double limitValue, Tariff tariff) {
        double gstPercent = tariff.getGstPercent() == null ? 0.0 : tariff.getGstPercent();
        double gstMultiplier = 1.0 + (gstPercent / 100.0);
        
        return switch (limitType.toUpperCase()) {
            case "AMOUNT" -> BigDecimal.valueOf(limitValue).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
            case "ENERGY" -> BigDecimal.valueOf(limitValue * tariff.getPricePerKwh() * gstMultiplier)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .doubleValue();
            case "TIME" -> {
                // Assume average charging power of 22kW
                double estimatedKwh = (limitValue / 60.0) * 22.0;
                yield BigDecimal.valueOf(estimatedKwh * tariff.getPricePerKwh() * gstMultiplier)
                        .setScale(2, java.math.RoundingMode.HALF_UP)
                        .doubleValue();
            }
            default -> throw new IllegalArgumentException("Invalid limit type: " + limitType);
        };
    }

    private void cleanupStaleConnectorSessions(Long chargerId, Integer connectorNo) {
        LocalDateTime now = LocalDateTime.now();

        chargingSessionRepository
                .findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(chargerId, connectorNo, "PENDING_START")
                .ifPresent(session -> {
                    boolean tooOld = session.getCreatedAt() != null
                            && Duration.between(session.getCreatedAt(), now).toMinutes() >= 3;
                    boolean noTransaction = session.getOcppTransactionId() == null;
                    if (tooOld && noTransaction) {
                        logger.warn("Expiring stale PENDING_START sessionId={} chargerId={} connectorNo={}",
                                session.getId(), chargerId, connectorNo);
                        session.setStatus("FAILED");
                        session.setEndedAt(now);
                        chargingSessionRepository.save(session);
                    }
                });

        chargingSessionRepository
                .findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(chargerId, connectorNo, "PENDING_VERIFICATION")
                .ifPresent(session -> {
                    boolean tooOld = session.getCreatedAt() != null
                            && Duration.between(session.getCreatedAt(), now).toMinutes() >= 15;
                    if (tooOld) {
                        logger.warn("Expiring stale PENDING_VERIFICATION sessionId={} chargerId={} connectorNo={}",
                                session.getId(), chargerId, connectorNo);
                        session.setStatus("FAILED");
                        session.setEndedAt(now);
                        chargingSessionRepository.save(session);
                    }
                });
    }

    @PostMapping("/{id}/verify-connector")
    public ResponseEntity<?> verifyConnector(@PathVariable Long id, HttpServletRequest httpRequest) {
        try {
            chargingSessionService.verifyConnectorAwaitingPayment(id);
            
            // Fetch the session again to get updated status for response
            ChargingSession session = chargingSessionRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + id));
            
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionId", id);
            response.put("message", "Connector verified. Please complete payment to start charging.");
            response.put("status", session.getStatus());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            logger.error("Failed verify-connector for sessionId={}", id, ex);
            throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to initiate charging: " + ex.getMessage(),
                ex
            );
        }
    }

    @PostMapping("/{id}/cancel")
    @Transactional
    public ResponseEntity<?> cancelSession(@PathVariable Long id) {
        ChargingSession session = chargingSessionRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + id));

        String status = session.getStatus();
        if (!java.util.List.of("PENDING_VERIFICATION", "PENDING_PAYMENT").contains(status)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot cancel session in status: " + status));
        }

        session.setStatus("CANCELLED");
        session.setEndedAt(LocalDateTime.now());

        if ("PENDING_PAYMENT".equalsIgnoreCase(status)
                && session.getPreauthId() != null
                && session.getPreauthAmount() != null
                && session.getPreauthAmount() > 0) {
            try {
                paymentService.release(session.getPreauthId(), BigDecimal.valueOf(session.getPreauthAmount()));
                session.setPaymentStatus("PREAUTH_RELEASED");
                logger.info("Released preauth {} for cancelled session {}", session.getPreauthId(), session.getId());
            } catch (Exception ex) {
                logger.warn("Failed to release preauth {} for cancelled session {}: {}",
                        session.getPreauthId(), session.getId(), ex.getMessage());
            }
        }

        chargingSessionRepository.save(session);

        // Restore connector to Available so it can accept new sessions
        try {
            Long connectorId = chargingSessionRepository.findConnectorIdBySessionId(id).orElse(null);
            if (connectorId != null) {
                connectorRepository.updateStatusById(connectorId, "Available");
            }
        } catch (Exception ex) {
            logger.warn("Could not restore connector status for cancelled sessionId={}: {}", id, ex.getMessage());
        }

        logger.info("Session {} cancelled and connector restored to Available", id);
        return ResponseEntity.ok(Map.of("message", "Session cancelled", "sessionId", id));
    }

    @PostMapping("/{id}/pay-and-start")
    public ResponseEntity<?> payAndStart(@PathVariable Long id) {        try {
            chargingSessionService.acceptPaymentAndStartCharging(id);
            ChargingSession session = chargingSessionRepository.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session not found: " + id));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sessionId", id);
            response.put("message", "Payment accepted. Charging will start shortly.");
            response.put("status", session.getStatus());
            response.put("paymentStatus", session.getPaymentStatus());
            response.put("preauthId", session.getPreauthId());
            return ResponseEntity.ok(response);
        } catch (IllegalStateException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        } catch (RuntimeException ex) {
            logger.error("Failed pay-and-start for sessionId={}", id, ex);
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to process payment/start charging: " + ex.getMessage(),
                    ex
            );
        }
    }

    @PostMapping("/{id}/stop")
    @Audit
    @Transactional
    public ResponseEntity<?> stopSession(@PathVariable Long id, HttpServletRequest httpRequest) {
        ChargingSession session = getSessionOrThrow(id);
        Charger charger = session.getCharger();

        // Validate owner access to station (if authorization header present)
        try {
            String authHeader = httpRequest.getHeader("Authorization");
            ownerStationAccessAspect.validateOwnerStationAccess(charger.getStation().getId(), authHeader);
        } catch (IllegalAccessException ex) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", ex.getMessage()));
        }

        boolean remoteStopSent = false;
        if (session.getOcppTransactionId() != null) {
            remoteStopSent = ocppWebSocketHandler.sendRemoteStop(charger.getOcppIdentity(), session.getOcppTransactionId());
        }

        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
        long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);
        sessionMaxMeterCache.remove(id);
        chargingSessionService.completeSessionImmediately(session.getId(), latestMeterWh);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("message", remoteStopSent
            ? "Charging stopped and payment settled."
            : "Charger offline; session force-stopped and payment settled.");
        response.put("mode", "IMMEDIATE_COMPLETION");
        response.put("sessionId", session.getId());
        return ResponseEntity.ok(response);
    }

    /**
     * Force-stop: tries RemoteStopTransaction first, then falls back to Soft Reset if charger ignores it.
     * Always completes the CMS session regardless of charger response.
     */
    @PostMapping("/{id}/force-stop")
    @Audit
    @Transactional
    public ResponseEntity<?> forceStopSession(@PathVariable Long id) {
        ChargingSession session = getSessionOrThrow(id);
        Charger charger = session.getCharger();

        String ocppResult = "NO_TRANSACTION_ID";
        if (session.getOcppTransactionId() != null) {
            ocppResult = ocppWebSocketHandler.forceStopCharger(
                charger.getOcppIdentity(), session.getOcppTransactionId());
        }

        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
        long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);
        sessionMaxMeterCache.remove(id);
        chargingSessionService.completeSessionImmediately(session.getId(), latestMeterWh);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.getId());
        response.put("ocppResult", ocppResult);
        response.put("message", switch (ocppResult) {
            case "REMOTE_STOP_ACCEPTED"    -> "Charger accepted RemoteStopTransaction. Session completed.";
            case "SOFT_RESET_ACCEPTED"     -> "RemoteStop rejected; Soft Reset sent and accepted. Session completed.";
            case "OFFLINE"                 -> "Charger offline; CMS session force-completed.";
            case "BOTH_FAILED"             -> "Charger ignored both RemoteStop and Reset; CMS session force-completed.";
            default                        -> "CMS session force-completed. Charger responded: " + ocppResult;
        });
        response.put("mode", "FORCE_STOP");
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/live")
    @Audit
    public ResponseEntity<?> getLiveSession(@PathVariable Long id) {
        ChargingSession session = getSessionOrThrow(id);

        Charger charger = session.getCharger();
        Tariff tariff = tariffRepository.findByStation_Id(charger.getStation().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tariff not found for station " + charger.getStation().getId()));

        // Guard against stuck sessions when RemoteStart was never accepted or charger is disconnected.
        if ("PENDING_START".equalsIgnoreCase(session.getStatus()) && session.getOcppTransactionId() == null) {
            boolean chargerOnline = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity());
            boolean stalePendingStart = session.getCreatedAt() != null
                    && Duration.between(session.getCreatedAt(), LocalDateTime.now()).toMinutes() >= 3;
            if (!chargerOnline || stalePendingStart) {
                logger.warn("Auto-failing stale/offline PENDING_START sessionId={} chargerId={} online={} stale={}",
                        session.getId(), charger.getOcppIdentity(), chargerOnline, stalePendingStart);
                session.setStatus("FAILED");
                session.setEndedAt(LocalDateTime.now());
                chargingSessionRepository.save(session);
                session = getSessionOrThrow(id);
            }
        }

        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(id);
        long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);
        double currentPowerKw = latestMeterOptional.map(v -> (v.getPowerW() == null ? 0.0 : v.getPowerW() / 1000.0)).orElse(0.0);

        double energyConsumedKwh = Math.max(0, (latestMeterWh - meterStart) / 1000.0);
        long cachedMax = sessionMaxMeterCache.getOrDefault(id, meterStart);
        logger.debug("[LIVE-DEBUG] Session {} meterStart={} cachedMax={} latestMeterWh={} energyKwh={} power={} status={}",
            id, meterStart, cachedMax, latestMeterWh, energyConsumedKwh, currentPowerKw, session.getStatus());
        double baseAmount = energyConsumedKwh * tariff.getPricePerKwh();
        double gstPercent = tariff.getGstPercent() == null ? 0.0 : tariff.getGstPercent();
        double runningAmountRs = baseAmount + (baseAmount * gstPercent / 100.0);
        double gstAmountRs = runningAmountRs - baseAmount;

        if ("ACTIVE".equalsIgnoreCase(session.getStatus())
            && session.getLimitType() != null
            && session.getLimitValue() != null) {
            String limitType = session.getLimitType().toUpperCase();
            double limitValue = session.getLimitValue();
            boolean shouldComplete = false;

            if ("AMOUNT".equals(limitType)) {
                double effectiveBudget = limitValue;
                if (session.getPreauthAmount() != null && session.getPreauthAmount() > 0) {
                    effectiveBudget = Math.min(effectiveBudget, session.getPreauthAmount());
                }
                shouldComplete = runningAmountRs >= effectiveBudget;
            } else if ("ENERGY".equals(limitType)) {
                shouldComplete = energyConsumedKwh >= limitValue;
            } else if ("TIME".equals(limitType)) {
                long elapsedMinutes = session.getStartedAt() == null
                    ? 0
                    : ChronoUnit.MINUTES.between(session.getStartedAt(), LocalDateTime.now());
                shouldComplete = elapsedMinutes >= limitValue;
            }

            if (shouldComplete) {
                sessionMaxMeterCache.remove(id);
                chargingSessionService.completeSessionImmediately(id, latestMeterWh);
                session = getSessionOrThrow(id);
            }
        }

        if ("STOPPING".equalsIgnoreCase(session.getStatus())
            && session.getUpdatedAt() != null
            && ChronoUnit.SECONDS.between(session.getUpdatedAt(), LocalDateTime.now()) >= 5) {
            sessionMaxMeterCache.remove(id);
            chargingSessionService.completeSessionImmediately(id, latestMeterWh);
            session = getSessionOrThrow(id);
        }

        boolean hasChargingBegun = session.getStartedAt() != null && Set.of("ACTIVE", "STOPPING", "COMPLETED")
            .contains(session.getStatus() == null ? "" : session.getStatus().toUpperCase());
        long elapsedSeconds = hasChargingBegun
            ? ChronoUnit.SECONDS.between(session.getStartedAt(), LocalDateTime.now())
            : 0;

        boolean completed = "COMPLETED".equalsIgnoreCase(session.getStatus());
        double responseEnergyConsumedKwh = completed
            ? (session.getEnergyConsumedKwh() == null ? energyConsumedKwh : session.getEnergyConsumedKwh())
            : energyConsumedKwh;
        double responseBaseAmount = completed
            ? (session.getBaseAmount() == null ? baseAmount : session.getBaseAmount())
            : baseAmount;
        double responseGstAmount = completed
            ? (session.getGstAmount() == null ? gstAmountRs : session.getGstAmount())
            : gstAmountRs;
        double responseRunningAmount = completed
            ? (session.getTotalAmount() == null ? runningAmountRs : session.getTotalAmount())
            : runningAmountRs;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", id);
        response.put("vehicleNumber", session.getVehicleNumber());
        response.put("status", session.getStatus());
        response.put("energyConsumedKwh", responseEnergyConsumedKwh);
        response.put("baseAmountRs", java.math.BigDecimal.valueOf(responseBaseAmount).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
        response.put("gstAmountRs", java.math.BigDecimal.valueOf(responseGstAmount).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
        response.put("runningAmountRs", java.math.BigDecimal.valueOf(responseRunningAmount).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
        response.put("elapsedSeconds", Math.max(elapsedSeconds, 0));
        response.put("currentPowerKw", (completed || !hasChargingBegun) ? 0.0 : currentPowerKw);
        response.put("meterStart", meterStart);
        response.put("latestMeterWh", latestMeterWh);
        response.put("startedAt", session.getStartedAt());
        response.put("limitType", session.getLimitType());
        response.put("limitValue", session.getLimitValue());
        Integer txId = session.getOcppTransactionId();
        Double latestSoc = ocppWebSocketHandler.getLatestSoc(txId);
        response.put("socPercent", latestSoc);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/bill")
    @Audit
    public ResponseEntity<?> getBill(@PathVariable Long id) {
        ChargingSession session = getSessionOrThrow(id);
        Charger charger = session.getCharger();
        Tariff tariff = tariffRepository.findByStation_Id(charger.getStation().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tariff not found for station " + charger.getStation().getId()));

        double energyConsumed = session.getEnergyConsumedKwh() == null ? 0.0 : session.getEnergyConsumedKwh();
        double preauthAmount = session.getPreauthAmount() == null ? 0.0 : session.getPreauthAmount();
        double refundAmount = session.getRefundAmount() == null ? 0.0 : session.getRefundAmount();
        double totalAmount = session.getTotalAmount() == null ? 0.0 : session.getTotalAmount();
        double chargedAmount = totalAmount > 0.0 ? totalAmount : Math.max(0.0, preauthAmount - refundAmount);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", id);
        response.put("vehicleNumber", session.getVehicleNumber());
        response.put("connectorNo", session.getConnectorNo());
        response.put("energyConsumedKwh", energyConsumed);
        response.put("pricePerKwh", tariff.getPricePerKwh());
        response.put("baseAmount", session.getBaseAmount() == null ? 0.0 : session.getBaseAmount());
        response.put("gstPercent", tariff.getGstPercent());
        response.put("gstAmount", session.getGstAmount() == null ? 0.0 : session.getGstAmount());
        response.put("totalAmount", totalAmount);
        response.put("chargedAmount", chargedAmount);
        response.put("preauthAmount", preauthAmount);
        response.put("refundAmount", refundAmount);
        response.put("platformFee", session.getPlatformFee() == null ? 0.0 : session.getPlatformFee());
        response.put("ownerRevenue", session.getOwnerRevenue() == null ? 0.0 : session.getOwnerRevenue());
        response.put("paymentMode", session.getPaymentMode());
        response.put("paymentStatus", session.getPaymentStatus());
        response.put("startedAt", session.getStartedAt());
        response.put("endedAt", session.getEndedAt());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/invoice")
    @Audit
    public ResponseEntity<?> getInvoice(@PathVariable Long id) {
        ChargingSession session = getSessionOrThrow(id);
        
        if (!"COMPLETED".equals(session.getStatus())) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invoice is only available for completed sessions"
            ));
        }

        Charger charger = session.getCharger();
        Tariff tariff = tariffRepository.findByStation_Id(charger.getStation().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tariff not found"));

        Map<String, Object> invoice = new LinkedHashMap<>();
        invoice.put("invoiceNumber", session.getInvoiceNumber());
        invoice.put("invoiceUrl", session.getInvoiceUrl());
        invoice.put("sessionId", session.getId());
        invoice.put("vehicleNumber", session.getVehicleNumber());
        invoice.put("phoneNumber", session.getPhoneNumber());
        invoice.put("stationName", charger.getStation().getName());
        invoice.put("chargerIdentity", charger.getOcppIdentity());
        invoice.put("connectorNo", session.getConnectorNo());
        
        invoice.put("startTime", session.getStartedAt());
        invoice.put("endTime", session.getEndedAt());
        invoice.put("duration", session.getStartedAt() != null && session.getEndedAt() != null
            ? ChronoUnit.MINUTES.between(session.getStartedAt(), session.getEndedAt()) : 0);
        
        invoice.put("energyConsumedKwh", session.getEnergyConsumedKwh());
        invoice.put("pricePerKwh", tariff.getPricePerKwh());
        invoice.put("baseAmount", session.getBaseAmount());
        invoice.put("gstPercent", tariff.getGstPercent());
        invoice.put("gstAmount", session.getGstAmount());
        invoice.put("totalAmount", session.getTotalAmount());
        invoice.put("platformFee", session.getPlatformFee());
        invoice.put("ownerRevenue", session.getOwnerRevenue());
        
        invoice.put("preauthAmount", session.getPreauthAmount());
        invoice.put("refundAmount", session.getRefundAmount());
        invoice.put("paymentMode", session.getPaymentMode());
        invoice.put("paymentStatus", session.getPaymentStatus());
        
        return ResponseEntity.ok(invoice);
    }

    @GetMapping("/{id}")
    @Audit
    public ChargingSession getSession(@PathVariable Long id) {
        return getSessionOrThrow(id);
    }

    @GetMapping("/charger/{ocppIdentity}/active")
    public ResponseEntity<?> getActiveSessionForCharger(@PathVariable String ocppIdentity) {
        Set<String> activeStatuses = Set.of("PENDING_VERIFICATION", "PENDING_PAYMENT", "PENDING_START", "ACTIVE", "STOPPING");

        Optional<ChargingSession> activeSessionOptional = chargingSessionRepository
                .findFirstByCharger_OcppIdentityAndStatusInOrderByCreatedAtDesc(ocppIdentity, activeStatuses);

        if (activeSessionOptional.isEmpty()) {
            return ResponseEntity.ok(Map.of("active", false));
        }

        ChargingSession session = activeSessionOptional.get();
        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
        long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("active", true);
        response.put("sessionId", session.getId());
        response.put("status", session.getStatus());
        response.put("connectorNo", session.getConnectorNo());
        response.put("transactionId", session.getOcppTransactionId());
        response.put("meterStart", meterStart);
        response.put("latestMeterWh", latestMeterWh);
        response.put("vehicleNumber", session.getVehicleNumber());
        response.put("phoneNumber", session.getPhoneNumber());
        response.put("startedBy", session.getStartedBy());
        response.put("paymentMode", session.getPaymentMode());
        response.put("paymentStatus", session.getPaymentStatus());
        response.put("limitType", session.getLimitType());
        response.put("limitValue", session.getLimitValue());
        response.put("startedAt", session.getStartedAt());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/customer/active")
    public ResponseEntity<?> getActiveSessionForCustomer(@RequestParam String phoneNumber) {
        String normalizedPhone = phoneNumber == null ? "" : phoneNumber.trim();
        if (normalizedPhone.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "phoneNumber is required"));
        }

        Set<String> activeStatuses = Set.of("PENDING_VERIFICATION", "PENDING_PAYMENT", "PENDING_START", "ACTIVE", "STOPPING");

        List<ChargingSession> sessions = chargingSessionRepository
                .findByPhoneNumberAndStatusInOrderByCreatedAtDesc(normalizedPhone, activeStatuses);

        if (sessions.isEmpty()) {
            return ResponseEntity.ok(Map.of("active", false));
        }

        ChargingSession session = sessions.get(0);
        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
        long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("active", true);
        response.put("sessionId", session.getId());
        response.put("status", session.getStatus());
        response.put("connectorNo", session.getConnectorNo());
        response.put("transactionId", session.getOcppTransactionId());
        response.put("meterStart", meterStart);
        response.put("latestMeterWh", latestMeterWh);
        response.put("vehicleNumber", session.getVehicleNumber());
        response.put("phoneNumber", session.getPhoneNumber());
        response.put("startedBy", session.getStartedBy());
        response.put("paymentMode", session.getPaymentMode());
        response.put("paymentStatus", session.getPaymentStatus());
        response.put("limitType", session.getLimitType());
        response.put("limitValue", session.getLimitValue());
        response.put("startedAt", session.getStartedAt());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/monitor/live")
    public ResponseEntity<?> getLiveMonitor() {
        long activeChargingSessions = chargingSessionRepository.countByStatus("ACTIVE");
        long pendingStartSessions = chargingSessionRepository.countByStatus("PENDING_START");
        long pendingVerificationSessions = chargingSessionRepository.countByStatus("PENDING_VERIFICATION");
        int connectedChargers = ocppWebSocketHandler.getActiveSessionCount();
        List<Map<String, Object>> recentConnectorEvents = ocppWebSocketHandler.getRecentConnectorEvents(80);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeChargingSessions", activeChargingSessions);
        response.put("pendingStartSessions", pendingStartSessions);
        response.put("pendingVerificationSessions", pendingVerificationSessions);
        response.put("connectedChargers", connectedChargers);
        response.put("recentConnectorEvents", recentConnectorEvents);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/monitor/active-sessions")
    public ResponseEntity<?> getActiveSessionsMonitor() {
        Set<String> activeStatuses = Set.of("PENDING_VERIFICATION", "PENDING_PAYMENT", "PENDING_START", "ACTIVE", "STOPPING");

        List<Map<String, Object>> sessions = chargingSessionRepository
            .findByStatusInWithChargerOrderByCreatedAtDesc(activeStatuses)
                .stream()
                .limit(100)
                .map(session -> {
                    long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
                    Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
                    long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);
                    double energyConsumedKwh = Math.max(0, (latestMeterWh - meterStart) / 1000.0);

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", session.getId());
                    item.put("status", session.getStatus());
                    item.put("chargerOcppIdentity", session.getCharger() == null ? null : session.getCharger().getOcppIdentity());
                    item.put("connectorNo", session.getConnectorNo());
                    item.put("transactionId", session.getOcppTransactionId());
                    item.put("vehicleNumber", session.getVehicleNumber());
                    item.put("phoneNumber", session.getPhoneNumber());
                    item.put("startedBy", session.getStartedBy());
                    item.put("paymentMode", session.getPaymentMode());
                    item.put("paymentStatus", session.getPaymentStatus());
                    item.put("limitType", session.getLimitType());
                    item.put("limitValue", session.getLimitValue());
                    item.put("startedAt", session.getStartedAt());
                    item.put("meterStart", meterStart);
                    item.put("latestMeterWh", latestMeterWh);
                    item.put("energyConsumedKwh", energyConsumedKwh);
                    return item;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", sessions.size(),
                "sessions", sessions
        ));
    }

    /**
     * Get active sessions for the authenticated owner
     * Filters by owner's assigned stations
     */
    @GetMapping("/owner/active-sessions")
    public ResponseEntity<?> getOwnerActiveSessions(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        
        logger.info("[OWNER-SESSIONS] Authorization header received: {}", authHeader != null ? "yes (" + authHeader.length() + " chars)" : "NO");
        if (authHeader != null) {
            logger.info("[OWNER-SESSIONS] Header starts with Bearer: {}", authHeader.startsWith("Bearer "));
            logger.info("[OWNER-SESSIONS] First 50 chars: {}", authHeader.substring(0, Math.min(50, authHeader.length())));
        }
        
        OwnerAuthService.AuthenticatedOwner owner = ownerStationAccessAspect.getOwnerIfPresent(authHeader);
        
        logger.info("[OWNER-SESSIONS] Owner extracted: {}", owner != null ? owner.ownerId() : "NULL");
        
        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Owner authentication required");
        }

        Set<String> activeStatuses = Set.of("PENDING_VERIFICATION", "PENDING_PAYMENT", "PENDING_START", "ACTIVE", "STOPPING");
        List<Long> ownerStationIds = owner.stationIds();

        // Get all active sessions and filter by owner's assigned stations
        List<Map<String, Object>> sessions = chargingSessionRepository
            .findByStatusInWithChargerOrderByCreatedAtDesc(activeStatuses)
                .stream()
                .filter(session -> {
                    Charger charger = session.getCharger();
                    return charger != null && 
                           charger.getStation() != null && 
                           ownerStationIds.contains(charger.getStation().getId());
                })
                .map(session -> {
                    long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
                    Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
                    long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);
                    double energyConsumedKwh = Math.max(0, (latestMeterWh - meterStart) / 1000.0);

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("sessionId", session.getId());
                    item.put("status", session.getStatus());
                    item.put("chargerId", session.getCharger().getId());
                    item.put("chargerName", session.getCharger().getName());
                    item.put("chargerOcppIdentity", session.getCharger().getOcppIdentity());
                    item.put("connectorId", session.getConnector().getId());
                    item.put("connectorNo", session.getConnectorNo());
                    item.put("transactionId", session.getOcppTransactionId());
                    item.put("vehicleNumber", session.getVehicleNumber());
                    item.put("phoneNumber", session.getPhoneNumber());
                    item.put("startedBy", session.getStartedBy());
                    item.put("paymentMode", session.getPaymentMode());
                    item.put("paymentStatus", session.getPaymentStatus());
                    item.put("limitType", session.getLimitType());
                    item.put("limitValue", session.getLimitValue());
                    item.put("startedAt", session.getStartedAt());
                    item.put("meterStart", meterStart);
                    item.put("latestMeterWh", latestMeterWh);
                    item.put("energyConsumedKwh", energyConsumedKwh);
                    return item;
                })
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", sessions.size(),
                "sessions", sessions
        ));
    }

    private long getLiveMeterWh(ChargingSession session, long meterStart, Optional<MeterValue> latestMeterOptional) {
        if (latestMeterOptional.isEmpty()) {
            return meterStart;
        }

        MeterValue latestMeter = latestMeterOptional.get();
        long rawMeterWh = latestMeter.getEnergyWh() == null ? meterStart : latestMeter.getEnergyWh();
        
        // Always check cache first to protect against stale meter values during status transitions
        long cachedMaxMeter = sessionMaxMeterCache.getOrDefault(session.getId(), meterStart);
        
        // If session is no longer ACTIVE, use cached max and clear cache
        if (!"ACTIVE".equalsIgnoreCase(session.getStatus())) {
            long finalMeterWh = Math.max(rawMeterWh, cachedMaxMeter);
            if (rawMeterWh < cachedMaxMeter) {
                logger.warn("[METER-STALE] Session {} (status={}) rawMeterWh={} < cachedMax={}, using cache finalMeterWh={}",
                    session.getId(), session.getStatus(), rawMeterWh, cachedMaxMeter, finalMeterWh);
            }
            sessionMaxMeterCache.remove(session.getId());
            return finalMeterWh;
        }

        if (latestMeter.getTimestamp() == null || latestMeter.getPowerW() == null || latestMeter.getPowerW() <= 0.0) {
            return Math.max(rawMeterWh, cachedMaxMeter);
        }

        long secondsSinceLastMeter = Math.max(0, ChronoUnit.SECONDS.between(latestMeter.getTimestamp(), LocalDateTime.now()));
        if (secondsSinceLastMeter == 0) {
            return Math.max(rawMeterWh, cachedMaxMeter);
        }

        long estimatedAdditionalWh = Math.round((latestMeter.getPowerW() * secondsSinceLastMeter) / 3600.0);
        long estimatedMeterWh = rawMeterWh + estimatedAdditionalWh;
        
        long computedMeterWh = Math.max(rawMeterWh, estimatedMeterWh);
        long finalMeterWh = Math.max(computedMeterWh, cachedMaxMeter);
        
        // Update cache with the maximum meter we've ever computed
        if (finalMeterWh > cachedMaxMeter) {
            sessionMaxMeterCache.put(session.getId(), finalMeterWh);
        }
        
        // Log any meter regression detected
        if (rawMeterWh < cachedMaxMeter) {
            logger.warn("[METER-STALE] Session {} rawMeterWh={} < cachedMax={}, using cache finalMeterWh={}",
                session.getId(), rawMeterWh, cachedMaxMeter, finalMeterWh);
        }
        
        return finalMeterWh;
    }

    @PatchMapping("/{id}/markpaid")
    @Audit
    public ResponseEntity<?> markPaid(@PathVariable Long id) {
        ChargingSession session = getSessionOrThrow(id);

        if (!"COMPLETED".equalsIgnoreCase(session.getStatus())) {
            long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
            Optional<MeterValue> latestMeterOptional = meterValueRepository.findLatestBySessionId(session.getId());
            long latestMeterWh = getLiveMeterWh(session, meterStart, latestMeterOptional);
            sessionMaxMeterCache.remove(id);
            chargingSessionService.completeSessionImmediately(session.getId(), latestMeterWh);
            session = getSessionOrThrow(id);
        }

        session.setPaymentStatus("PAID");
        chargingSessionRepository.save(session);
        upsertCompletedChargingLog(session);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionId", session.getId());
        response.put("status", session.getStatus());
        response.put("paymentStatus", session.getPaymentStatus());
        response.put("totalAmount", session.getTotalAmount());
        response.put("gstAmount", session.getGstAmount());
        response.put("baseAmount", session.getBaseAmount());
        response.put("platformFee", session.getPlatformFee());
        response.put("ownerRevenue", session.getOwnerRevenue());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/owner/completed-logs")
    public ResponseEntity<?> getOwnerCompletedLogs(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        OwnerAuthService.AuthenticatedOwner owner = ownerStationAccessAspect.getOwnerIfPresent(authHeader);

        if (owner == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Owner authentication required");
        }

        List<Long> stationIds = owner.stationIds();
        if (stationIds == null || stationIds.isEmpty()) {
            return ResponseEntity.ok(Map.of(
                "count", 0,
                "logs", List.of()
            ));
        }

        List<Map<String, Object>> logs = completedChargingLogRepository
                .findTop200ByStationIdInOrderByPaymentCompletedAtDesc(stationIds)
                .stream()
                .map(this::toCompletedLogResponse)
                .toList();

        return ResponseEntity.ok(Map.of(
                "count", logs.size(),
                "logs", logs
        ));
    }

    /**
     * Simulator Reset: Resets all chargers, connectors, and sessions to default state for testing
     */
    @PostMapping("/simulator/reset")
    @Transactional
    public ResponseEntity<?> resetSimulator() {
        logger.info("Simulator reset initiated");

        // Get all chargers
        List<Charger> allChargers = chargerRepository.findAll();
        int chargersReset = 0;
        for (Charger charger : allChargers) {
            charger.setStatus("Available");
            charger.setCommunicationStatus("Offline");
            chargersReset++;
        }
        chargerRepository.saveAll(allChargers);

        // Get all connectors
        List<Connector> allConnectors = connectorRepository.findAll();
        int connectorsReset = 0;
        for (Connector connector : allConnectors) {
            connector.setStatus("Available");
            connectorsReset++;
        }
        connectorRepository.saveAll(allConnectors);

        // Complete all active sessions
        Set<String> activeStatuses = Set.of(
            "PENDING_VERIFICATION",
            "PENDING_PAYMENT",
            "PENDING_START",
            "ACTIVE",
            "STOPPING"
        );
        List<ChargingSession> activeSessions = chargingSessionRepository.findByStatusInOrderByCreatedAtDesc(activeStatuses);
        int sessionsCompleted = 0;
        for (ChargingSession session : activeSessions) {
            session.setStatus("COMPLETED");
            session.setEndedAt(LocalDateTime.now());
            sessionsCompleted++;
        }
        chargingSessionRepository.saveAll(activeSessions);

        logger.info("Simulator reset complete: {} chargers, {} connectors, {} sessions", 
            chargersReset, connectorsReset, sessionsCompleted);

        return ResponseEntity.ok(Map.of(
            "message", "Simulator reset successful",
            "chargersReset", chargersReset,
            "connectorsReset", connectorsReset,
            "sessionsCompleted", sessionsCompleted
        ));
    }

    private ChargingSession getSessionOrThrow(Long id) {
        try {
            return chargingSessionService.getSessionById(id);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    private void upsertCompletedChargingLog(ChargingSession session) {
        CompletedChargingLog log = completedChargingLogRepository.findBySessionId(session.getId())
                .orElseGet(CompletedChargingLog::new);

        Charger charger = session.getCharger();

        log.setSessionId(session.getId());
        log.setStationId(charger != null && charger.getStation() != null ? charger.getStation().getId() : null);
        log.setStationName(charger != null && charger.getStation() != null ? charger.getStation().getName() : null);
        log.setChargerId(charger == null ? null : charger.getId());
        log.setChargerName(charger == null ? null : charger.getName());
        log.setChargerOcppIdentity(charger == null ? null : charger.getOcppIdentity());
        log.setConnectorNo(session.getConnectorNo());
        log.setVehicleNumber(session.getVehicleNumber());
        log.setPhoneNumber(session.getPhoneNumber());
        log.setStartedBy(session.getStartedBy());
        log.setPaymentMode(session.getPaymentMode());
        log.setPaymentStatus(session.getPaymentStatus());
        log.setEnergyConsumedKwh(session.getEnergyConsumedKwh());
        log.setAmountPaid(session.getTotalAmount());
        log.setStartedAt(session.getStartedAt());
        log.setEndedAt(session.getEndedAt());
        log.setPaymentCompletedAt(LocalDateTime.now());

        completedChargingLogRepository.save(log);
    }

    private Map<String, Object> toCompletedLogResponse(CompletedChargingLog log) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", log.getId());
        item.put("sessionId", log.getSessionId());
        item.put("stationId", log.getStationId());
        item.put("stationName", log.getStationName());
        item.put("chargerId", log.getChargerId());
        item.put("chargerName", log.getChargerName());
        item.put("chargerOcppIdentity", log.getChargerOcppIdentity());
        item.put("connectorNo", log.getConnectorNo());
        item.put("vehicleNumber", log.getVehicleNumber());
        item.put("phoneNumber", log.getPhoneNumber());
        item.put("startedBy", log.getStartedBy());
        item.put("paymentMode", log.getPaymentMode());
        item.put("paymentStatus", log.getPaymentStatus());
        item.put("energyConsumedKwh", log.getEnergyConsumedKwh());
        item.put("amountPaid", log.getAmountPaid());
        item.put("startedAt", log.getStartedAt());
        item.put("endedAt", log.getEndedAt());
        item.put("paymentCompletedAt", log.getPaymentCompletedAt());
        return item;
    }
}
