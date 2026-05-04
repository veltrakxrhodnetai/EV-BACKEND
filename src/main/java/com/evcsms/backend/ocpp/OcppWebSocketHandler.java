package com.evcsms.backend.ocpp;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.ChargingSession;
import com.evcsms.backend.model.Connector;
import com.evcsms.backend.model.MeterValue;
import com.evcsms.backend.model.OcppConfiguration;
import com.evcsms.backend.model.OcppMessageLog;
import com.evcsms.backend.model.Tariff;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.MeterValueRepository;
import com.evcsms.backend.repository.OcppConfigurationRepository;
import com.evcsms.backend.repository.OcppMessageLogRepository;
import com.evcsms.backend.repository.TariffRepository;
import com.evcsms.backend.service.Msg91OtpService;
import com.evcsms.backend.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Arrays;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component("backendOcppWebSocketHandler")
public class OcppWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(OcppWebSocketHandler.class);
    private static final double GST_PERCENT = 18.0;
    private static final double DEFAULT_PLATFORM_FEE_PERCENT = 12.0;

    private final ObjectMapper objectMapper;
    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;
    private final ChargingSessionRepository chargingSessionRepository;
    private final TariffRepository tariffRepository;
    private final MeterValueRepository meterValueRepository;
    private final OcppConfigurationRepository ocppConfigurationRepository;
    private final OcppMessageLogRepository ocppMessageLogRepository;
    private final Msg91OtpService msg91OtpService;
    private final PaymentService paymentService;
    private final ConcurrentHashMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingResponses = new ConcurrentHashMap<>();
    // transactionId -> latest SoC (0-100)
    private final ConcurrentHashMap<Integer, Double> latestSocByTransaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> latestEnergyWhByTransaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, LocalDateTime> lastEnergyProgressAtByTransaction = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> staleChargingMitigationAt = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> recentConnectorEvents = new ConcurrentLinkedDeque<>();
    private final ConcurrentHashMap<String, Deque<UnknownConnectorAlert>> unknownConnectorAlerts = new ConcurrentHashMap<>();
    private static final int MAX_RECENT_EVENTS = 300;
    private static final int MAX_UNKNOWN_ALERTS_PER_CHARGER = 30;
        private static final Set<String> ACTIVE_SESSION_STATUSES = Set.of(
            "PENDING_VERIFICATION",
            "PENDING_PAYMENT",
            "PENDING_START",
            "ACTIVE",
            "STOPPING"
        );

    @Value("${app.charging.ac-no-energy-timeout-seconds:60}")
    private int acNoEnergyTimeoutSeconds;

    @Value("${app.ocpp.presence.minimum-offline-threshold-seconds:90}")
    private int minimumOfflineThresholdSeconds;

    @Value("${app.ocpp.presence.heartbeat-miss-multiplier:3}")
    private int heartbeatMissMultiplier;

    @Value("${app.ocpp.presence.stale-charging-mitigation-cooldown-seconds:120}")
    private int staleChargingMitigationCooldownSeconds;

    public OcppWebSocketHandler(
            ObjectMapper objectMapper,
            ChargerRepository chargerRepository,
            ConnectorRepository connectorRepository,
            ChargingSessionRepository chargingSessionRepository,
            TariffRepository tariffRepository,
            MeterValueRepository meterValueRepository,
            OcppConfigurationRepository ocppConfigurationRepository,
                OcppMessageLogRepository ocppMessageLogRepository,
                Msg91OtpService msg91OtpService,
                PaymentService paymentService
    ) {
        this.objectMapper = objectMapper;
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
        this.chargingSessionRepository = chargingSessionRepository;
        this.tariffRepository = tariffRepository;
        this.meterValueRepository = meterValueRepository;
        this.ocppConfigurationRepository = ocppConfigurationRepository;
        this.ocppMessageLogRepository = ocppMessageLogRepository;
        this.msg91OtpService = msg91OtpService;
        this.paymentService = paymentService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String chargerId = extractChargerId(session);

        // Enforce token authentication when securityMode is TOKEN
        java.util.Optional<OcppConfiguration> configOpt =
                ocppConfigurationRepository.findByChargePointIdentity(chargerId);
        if (configOpt.isPresent()) {
            OcppConfiguration config = configOpt.get();
            if ("TOKEN".equalsIgnoreCase(config.getSecurityMode())) {
                String expectedToken = config.getTokenValue();
                String providedToken = extractBearerToken(session);
                if (expectedToken == null || !expectedToken.equals(providedToken)) {
                    logger.warn("OCPP auth failed for chargerId={}: invalid or missing token", chargerId);
                    session.close(CloseStatus.NOT_ACCEPTABLE.withReason("Unauthorized"));
                    return;
                }
            }
        }

        activeSessions.put(chargerId, session);
        chargerRepository.findByOcppIdentity(chargerId).ifPresent(charger -> {
            charger.setCommunicationStatus("ONLINE");
            charger.setLastHeartbeat(LocalDateTime.now());
            chargerRepository.save(charger);
        });
        logger.info("OCPP connection established for chargerId={}, remote={}, uri={}, protocol={}",
            chargerId,
            session.getRemoteAddress(),
            session.getUri(),
            session.getAcceptedProtocol());
        recordConnectorEvent("CONNECTION_OPEN", chargerId, null, "CONNECTED", null, null,
                "WebSocket connection established");
    }

    /**
     * Extracts the token from the HTTP Authorization header.
     * Supports both "Authorization: Bearer <token>" and
     * OCPP Basic Auth "Authorization: Basic base64(identity:password)" where password = token.
     */
    private String extractBearerToken(WebSocketSession session) {
        List<String> authHeaders = session.getHandshakeHeaders().get("Authorization");
        if (authHeaders == null || authHeaders.isEmpty()) {
            return extractTokenFromQuery(session);
        }
        String header = authHeaders.get(0);
        if (header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        if (header.startsWith("Basic ")) {
            try {
                String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
                        StandardCharsets.UTF_8);
                // OCPP Basic: identity:password — password is the token
                int colon = decoded.indexOf(':');
                if (colon >= 0) {
                    return decoded.substring(colon + 1);
                }
            } catch (IllegalArgumentException ignored) {
                // malformed Base64
            }
        }
        return extractTokenFromQuery(session);
    }

    // Browser WebSocket clients cannot set custom Authorization headers.
    // Support token via query string: ws://.../chargerId?token=YOUR_TOKEN
    private String extractTokenFromQuery(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return null;
        }

        return Arrays.stream(uri.getQuery().split("&"))
                .map(part -> part.split("=", 2))
                .filter(kv -> kv.length == 2 && "token".equalsIgnoreCase(kv[0]))
                .map(kv -> java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8))
                .findFirst()
                .orElse(null);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        JsonNode root = objectMapper.readTree(message.getPayload());
        if (!root.isArray() || root.size() < 3) {
            return;
        }

        int messageType = root.path(0).asInt(-1);
        String chargerId = extractChargerId(session);

        logInboundMessage(chargerId, messageType, root);

        if (messageType == 3) {
            completePendingResponse(root.path(1).asText(""), root.path(2), null);
            return;
        }

        if (messageType == 4) {
            completePendingResponse(root.path(1).asText(""), null, root.path(2));
            return;
        }

        if (messageType != 2 || root.size() < 4) {
            return;
        }

        String msgId = root.path(1).asText("");
        String action = root.path(2).asText("");
        JsonNode payload = root.path(3);

        try {
            switch (action) {
                case "BootNotification" -> handleBootNotification(session, msgId, chargerId);
                case "Heartbeat" -> handleHeartbeat(session, msgId, chargerId);
                case "StatusNotification" -> handleStatusNotification(session, msgId, chargerId, payload);
                case "Authorize" -> sendCallResult(session, msgId, Map.of("idTagInfo", Map.of("status", "Accepted")));
                case "StartTransaction" -> handleStartTransaction(session, msgId, chargerId, payload);
                case "MeterValues" -> handleMeterValues(session, msgId, payload);
                case "StopTransaction" -> handleStopTransaction(session, msgId, payload);
                default -> sendCallResult(session, msgId, Map.of());
            }
        } catch (Exception ex) {
            // Never let an exception propagate out of this handler — doing so causes Spring
            // WebSocket to close the connection, which disconnects the charger.
            logger.error("[OCPP-ERROR] Unhandled exception processing action={} chargerId={} msgId={}: {}",
                action, chargerId, msgId, ex.getMessage(), ex);
            try {
                // Send a CallError back so the charger knows something went wrong.
                String callError = objectMapper.writeValueAsString(
                    new Object[]{4, msgId, "InternalError", ex.getMessage(), Map.of()});
                session.sendMessage(new TextMessage(callError));
            } catch (Exception sendEx) {
                logger.warn("[OCPP-ERROR] Could not send CallError to charger={}: {}", chargerId, sendEx.getMessage());
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String chargerId = extractChargerId(session);
        activeSessions.remove(chargerId);
        chargerRepository.findByOcppIdentity(chargerId).ifPresent(charger -> {
            charger.setCommunicationStatus("OFFLINE");
            chargerRepository.save(charger);
        });
        logger.info("OCPP connection closed for chargerId={}, status={}, uri={}, protocol={}",
            chargerId,
            status,
            session.getUri(),
            session.getAcceptedProtocol());
        recordConnectorEvent("CONNECTION_CLOSED", chargerId, null, "DISCONNECTED", null, null,
                "WebSocket connection closed: " + status);
    }

        public boolean sendRemoteStart(String ocppIdentity, int connectorId, String idTag) {
        WebSocketSession session = activeSessions.get(ocppIdentity);
        if (session == null || !session.isOpen()) {
            logger.warn("No active OCPP session found for charger {}", ocppIdentity);
            recordConnectorEvent("REMOTE_START_SKIPPED", ocppIdentity, connectorId, "NO_CONNECTION", null, null,
                    "RemoteStart skipped: no active OCPP session");
            return false;
        }

        try {
            JsonNode response = sendCommandAndAwait(ocppIdentity, "RS", "RemoteStartTransaction", Map.of(
                "connectorId", connectorId,
                "idTag", idTag
            ));
            String status = response.path("status").asText("Unknown");
            boolean accepted = "Accepted".equalsIgnoreCase(status);

            if (accepted) {
            logger.info("RemoteStartTransaction accepted chargerId={}, connectorId={}, idTag={}",
                ocppIdentity, connectorId, idTag);
            recordConnectorEvent("REMOTE_START_SENT", ocppIdentity, connectorId, "PENDING_START", null, null,
                "RemoteStartTransaction accepted by charger");
            } else {
            logger.warn("RemoteStartTransaction rejected chargerId={}, connectorId={}, status={}",
                ocppIdentity, connectorId, status);
            recordConnectorEvent("REMOTE_START_REJECTED", ocppIdentity, connectorId, status, null, null,
                "RemoteStartTransaction was not accepted by charger");
            }

            return accepted;
        } catch (IllegalStateException ex) {
            logger.warn("RemoteStartTransaction failed for charger={} connectorId={}: {}",
                ocppIdentity, connectorId, ex.getMessage());
            recordConnectorEvent("REMOTE_START_FAILED", ocppIdentity, connectorId, "FAILED", null, null,
                "RemoteStartTransaction failed: " + ex.getMessage());
            return false;
        }
    }

    public boolean sendRemoteStop(String ocppIdentity, int transactionId) {
        WebSocketSession session = activeSessions.get(ocppIdentity);
        if (session == null || !session.isOpen()) {
            logger.warn("No active OCPP session found for charger {}", ocppIdentity);
            return false;
        }

        try {
            JsonNode response = sendCommandAndAwait(ocppIdentity, "RS", "RemoteStopTransaction",
                Map.of("transactionId", transactionId));
            String status = response.path("status").asText("Unknown");
            logger.info("RemoteStopTransaction response for charger={} transactionId={} status={}",
                ocppIdentity, transactionId, status);
            return "Accepted".equalsIgnoreCase(status);
        } catch (IllegalStateException ex) {
            logger.warn("RemoteStopTransaction failed for charger={} transactionId={}: {}",
                ocppIdentity, transactionId, ex.getMessage());
            return false;
        }
    }

    /**
     * Force-stops a charger by sending RemoteStopTransaction, then falling back to Soft Reset
     * if the charger doesn't accept remote stop. Returns a description of what was attempted.
     */
    public String forceStopCharger(String ocppIdentity, int transactionId) {
        WebSocketSession session = activeSessions.get(ocppIdentity);
        if (session == null || !session.isOpen()) {
            return "OFFLINE";
        }

        // Step 1: try RemoteStopTransaction
        boolean stopped = sendRemoteStop(ocppIdentity, transactionId);
        if (stopped) {
            return "REMOTE_STOP_ACCEPTED";
        }

        // Step 2: fallback — Soft Reset
        logger.warn("RemoteStopTransaction not accepted for charger={}, attempting Soft Reset", ocppIdentity);
        try {
            JsonNode resetResponse = sendCommandAndAwait(ocppIdentity, "RST", "Reset", Map.of("type", "Soft"));
            String resetStatus = resetResponse.path("status").asText("Unknown");
            logger.info("Soft Reset fallback for charger={} status={}", ocppIdentity, resetStatus);
            return "SOFT_RESET_" + resetStatus.toUpperCase();
        } catch (IllegalStateException ex) {
            logger.warn("Soft Reset also failed for charger={}: {}", ocppIdentity, ex.getMessage());
            return "BOTH_FAILED";
        }
    }

    public OcppCommandResult changeAvailability(String ocppIdentity, int connectorId, String availabilityType) {
        Map<String, Object> payload = Map.of(
                "connectorId", connectorId,
                "type", availabilityType
        );
        JsonNode response = sendCommandAndAwait(ocppIdentity, "CA", "ChangeAvailability", payload);
        return new OcppCommandResult(
                response.path("status").asText("Unknown"),
                response,
                "ChangeAvailability"
        );
    }

    public OcppCommandResult reset(String ocppIdentity, String resetType) {
        JsonNode response = sendCommandAndAwait(ocppIdentity, "RST", "Reset", Map.of("type", resetType));
        return new OcppCommandResult(
                response.path("status").asText("Unknown"),
                response,
                "Reset"
        );
    }

    public OcppCommandResult unlockConnector(String ocppIdentity, int connectorId) {
        JsonNode response = sendCommandAndAwait(ocppIdentity, "ULK", "UnlockConnector", Map.of("connectorId", connectorId));
        return new OcppCommandResult(
                response.path("status").asText("Unknown"),
                response,
                "UnlockConnector"
        );
    }

    public OcppCommandResult getConfiguration(String ocppIdentity, List<String> keys) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (keys != null && !keys.isEmpty()) {
            payload.put("key", keys);
        }

        JsonNode response = sendCommandAndAwait(ocppIdentity, "GC", "GetConfiguration", payload);
        return new OcppCommandResult(
                response.path("configurationKey").isArray() ? "Accepted" : response.path("status").asText("Accepted"),
                response,
                "GetConfiguration"
        );
    }

    public OcppCommandResult changeConfiguration(String ocppIdentity, String key, String value) {
        JsonNode response = sendCommandAndAwait(ocppIdentity, "CC", "ChangeConfiguration", Map.of(
                "key", key,
                "value", value
        ));
        return new OcppCommandResult(
                response.path("status").asText("Unknown"),
                response,
                "ChangeConfiguration"
        );
    }

    public OcppCommandResult triggerMessage(String ocppIdentity, String requestedMessage, Integer connectorId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestedMessage", requestedMessage);
        if (connectorId != null) {
            payload.put("connectorId", connectorId);
        }

        JsonNode response = sendCommandAndAwait(ocppIdentity, "TM", "TriggerMessage", payload);
        return new OcppCommandResult(
                response.path("status").asText("Unknown"),
                response,
                "TriggerMessage"
        );
    }

    public OcppCommandResult clearCache(String ocppIdentity) {
        JsonNode response = sendCommandAndAwait(ocppIdentity, "CLC", "ClearCache", Map.of());
        return new OcppCommandResult(
                response.path("status").asText("Unknown"),
                response,
                "ClearCache"
        );
    }

    private void handleBootNotification(WebSocketSession session, String msgId, String chargerId) throws IOException {
        updateChargerHeartbeat(chargerId);
        sendCallResult(session, msgId, Map.of(
                "status", "Accepted",
                "currentTime", Instant.now().toString(),
                "interval", 60
        ));
    }

    private void handleHeartbeat(WebSocketSession session, String msgId, String chargerId) throws IOException {
        updateChargerHeartbeat(chargerId);
        enforceAcNoEnergyTimeoutOnHeartbeat(chargerId);
        sendCallResult(session, msgId, Map.of("currentTime", Instant.now().toString()));
    }

    private void handleStatusNotification(WebSocketSession session, String msgId, String chargerId, JsonNode payload) throws IOException {
        int connectorId = payload.path("connectorId").asInt(0);
        String status = payload.path("status").asText("Unknown");
        String errorCode = payload.path("errorCode").asText("N/A");
        boolean staleChargingWithoutSession = false;
        int staleChargingConnectorId = connectorId;

        Charger charger = chargerRepository.findByOcppIdentity(chargerId)
                .orElseThrow(() -> new IllegalStateException("Charger not found: " + chargerId));

        if (connectorId == 0) {
            boolean hasAnyActiveSession = chargingSessionRepository.findFirstByCharger_OcppIdentityAndStatusInOrderByCreatedAtDesc(
                chargerId, ACTIVE_SESSION_STATUSES).isPresent();
            if ("Charging".equalsIgnoreCase(status) && !hasAnyActiveSession) {
            staleChargingWithoutSession = true;
            staleChargingConnectorId = 0;
            }
            if (shouldNormalizeToAvailable(status)
                && !hasAnyActiveSession) {
                logger.info("Normalizing charge point status {} -> Available for charger {} (no active session)",
                        status, chargerId);
                status = "Available";
            }
            charger.setStatus(status);
            chargerRepository.save(charger);
            logger.info("Connector log: chargerId={}, connectorId=0 (charge point), status={}, errorCode={}", chargerId, status, errorCode);
            recordConnectorEvent("STATUS_NOTIFICATION", chargerId, 0, status, null, null,
                    "Charge point status updated, errorCode=" + errorCode);
        } else if (connectorId >= 1) {
            boolean hasActiveSession = chargingSessionRepository.existsByCharger_IdAndConnectorNoAndStatusIn(
                    charger.getId(), connectorId, ACTIVE_SESSION_STATUSES);

            if ("Charging".equalsIgnoreCase(status) && !hasActiveSession) {
                staleChargingWithoutSession = true;
            }

            if (shouldNormalizeToAvailable(status) && !hasActiveSession) {
                logger.info("Normalizing connector status {} -> Available for charger {} connector {} (no active session)",
                        status, chargerId, connectorId);
                status = "Available";
            }

            Connector connector = connectorRepository.findByCharger_IdAndConnectorNo(charger.getId(), connectorId).orElse(null);
            if (connector == null) {
                logger.warn("Connector log: chargerId={}, connectorId={} not found in DB while status={}", chargerId, connectorId, status);
                recordConnectorEvent("STATUS_NOTIFICATION", chargerId, connectorId, status, null, null,
                        "Connector not found in DB, errorCode=" + errorCode);
                recordUnknownConnectorAlert(chargerId, connectorId, status, errorCode);
            } else {
                connector.setStatus(status);
                connectorRepository.save(connector);
                logger.info("Connector log: chargerId={}, connectorId={}, connectorDbId={}, status={}, errorCode={}",
                        chargerId, connectorId, connector.getId(), status, errorCode);
                recordConnectorEvent("STATUS_NOTIFICATION", chargerId, connectorId, status, null, null,
                        "Connector status updated, connectorDbId=" + connector.getId() + ", errorCode=" + errorCode);
            }

                if ("Finishing".equalsIgnoreCase(status)) {
                chargingSessionRepository
                    .findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(charger.getId(), connectorId, "ACTIVE")
                    .ifPresent(activeSession -> {
                        long latestMeterWh = meterValueRepository.findLatestBySessionId(activeSession.getId())
                            .map(MeterValue::getEnergyWh)
                            .orElse(activeSession.getMeterStart() == null ? 0L : activeSession.getMeterStart());
                        logger.info("Finishing status received; completing active sessionId={} chargerId={} connectorId={} using latestMeterWh={}",
                            activeSession.getId(), chargerId, connectorId, latestMeterWh);
                        completeSessionFromMeter(activeSession, latestMeterWh, "STATUS_FINISHING", activeSession.getOcppTransactionId());
                    });
                }
        }

        logger.debug("StatusNotification processed chargerId={}, connectorId={}, status={}", chargerId, connectorId, status);
        sendCallResult(session, msgId, Map.of());

        // Important: only send further OCPP commands after ACKing StatusNotification to avoid call deadlocks.
        if (staleChargingWithoutSession) {
            mitigateStaleChargingWithoutSession(chargerId, staleChargingConnectorId);
        }
    }

    private boolean shouldNormalizeToAvailable(String status) {
        return "Finishing".equalsIgnoreCase(status)
                || "Unavailable".equalsIgnoreCase(status)
                || "Charging".equalsIgnoreCase(status);
    }

        private void mitigateStaleChargingWithoutSession(String chargerId, int connectorId) {
        String key = chargerId + ":" + connectorId;
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastAttempt = staleChargingMitigationAt.get(key);
        if (lastAttempt != null && ChronoUnit.SECONDS.between(lastAttempt, now) < Math.max(15, staleChargingMitigationCooldownSeconds)) {
            return;
        }
        staleChargingMitigationAt.put(key, now);

        logger.warn("Detected stale Charging state without active session for charger={} connector={}. Sending mitigation commands.",
            chargerId, connectorId);
        recordConnectorEvent("STALE_CHARGING_DETECTED", chargerId, connectorId, "CHARGING", null, null,
            "No active session exists but charger reported Charging");

        try {
            OcppCommandResult inop = changeAvailability(chargerId, connectorId, "Inoperative");
            recordConnectorEvent("STALE_CHARGING_MITIGATION", chargerId, connectorId, inop.status(), null, null,
                "ChangeAvailability(Inoperative) sent for stale charging on connectorId=" + connectorId);

            if (!inop.isAccepted()) {
            OcppCommandResult stationInop = changeAvailability(chargerId, 0, "Inoperative");
            recordConnectorEvent("STALE_CHARGING_MITIGATION", chargerId, 0, stationInop.status(), null, null,
                "ChangeAvailability(Inoperative) sent for whole charge point after connector-level rejection");

            if (!stationInop.isAccepted()) {
                OcppCommandResult softReset = reset(chargerId, "Soft");
                recordConnectorEvent("STALE_CHARGING_MITIGATION", chargerId, connectorId, softReset.status(), null, null,
                    "Soft Reset sent after ChangeAvailability rejection");

                if (!softReset.isAccepted()) {
                OcppCommandResult hardReset = reset(chargerId, "Hard");
                recordConnectorEvent("STALE_CHARGING_MITIGATION", chargerId, connectorId, hardReset.status(), null, null,
                    "Hard Reset sent after Soft Reset rejection");
                }
            }
            }
        } catch (Exception ex) {
            logger.warn("Stale charging mitigation failed for charger={} connector={}: {}",
                chargerId, connectorId, ex.getMessage());
            recordConnectorEvent("STALE_CHARGING_MITIGATION_FAILED", chargerId, connectorId, "FAILED", null, null,
                "Mitigation failed: " + ex.getMessage());
        }
        }

    private void handleStartTransaction(WebSocketSession session, String msgId, String chargerId, JsonNode payload) throws IOException {
        int connectorId = payload.path("connectorId").asInt(1);
        long meterStart = payload.path("meterStart").asLong(0L);
        String idTag = payload.path("idTag").asText(null);
        logger.info("StartTransaction received chargerId={}, connectorId={}, idTag={}, meterStart={}", chargerId, connectorId, idTag, meterStart);

        Charger charger = chargerRepository.findByOcppIdentity(chargerId)
            .orElseThrow(() -> new IllegalStateException("Charger not found: " + chargerId));

        Optional<ChargingSession> pendingStartSession = chargingSessionRepository
            .findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(
                charger.getId(),
                connectorId,
                "PENDING_START"
            );

        if (pendingStartSession.isEmpty()) {
            Optional<ChargingSession> activeSession = chargingSessionRepository
                .findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(
                    charger.getId(),
                    connectorId,
                    "ACTIVE"
                );

            if (activeSession.isPresent() && activeSession.get().getOcppTransactionId() != null) {
                Integer existingTxnId = activeSession.get().getOcppTransactionId();
                logger.warn("StartTransaction duplicate/late for chargerId={} connectorId={}, reusing active sessionId={} transactionId={}",
                    chargerId, connectorId, activeSession.get().getId(), existingTxnId);
                sendCallResult(session, msgId, Map.of(
                    "transactionId", existingTxnId,
                    "idTagInfo", Map.of("status", "Accepted")
                ));
                return;
            }

            logger.warn("StartTransaction without pending session chargerId={} connectorId={} idTag={}",
                chargerId, connectorId, idTag);
            recordConnectorEvent("START_TRANSACTION_ORPHAN", chargerId, connectorId, "REJECTED", null, null,
                "No pending/active session when StartTransaction arrived");
            sendCallResult(session, msgId, Map.of(
                "transactionId", 0,
                "idTagInfo", Map.of("status", "Invalid")
            ));
            // If charger attempted to start without a valid CMS session, force it back to safe state.
            mitigateStaleChargingWithoutSession(chargerId, connectorId);
            return;
        }

        ChargingSession chargingSession = pendingStartSession.get();

        int nextOcppTransactionId = chargingSessionRepository.findMaxOcppTransactionId() + 1;
        chargingSession.setOcppTransactionId(nextOcppTransactionId);
        chargingSession.setMeterStart(meterStart);
        chargingSession.setStatus("ACTIVE");
        chargingSession.setStartedAt(LocalDateTime.now());
        chargingSessionRepository.save(chargingSession);
        logger.info("Session activated sessionId={}, chargerId={}, connectorId={}, ocppTxnId={}",
            chargingSession.getId(), chargerId, connectorId, nextOcppTransactionId);
        recordConnectorEvent("START_TRANSACTION", chargerId, connectorId, "ACTIVE",
            chargingSession.getId(), nextOcppTransactionId,
            "StartTransaction accepted and session activated");

        charger.setStatus("Charging");
        chargerRepository.save(charger);

        connectorRepository.findByCharger_IdAndConnectorNo(charger.getId(), connectorId)
                .ifPresent(connector -> {
                    connector.setStatus("Charging");
                    connectorRepository.save(connector);
                });

        sendCallResult(session, msgId, Map.of(
            "transactionId", nextOcppTransactionId,
                "idTagInfo", Map.of("status", "Accepted")
        ));
    }

    public void enforceNoSessionCharging(String chargerId, Integer connectorId) {
        int safeConnectorId = connectorId == null ? 0 : connectorId;
        mitigateStaleChargingWithoutSession(chargerId, safeConnectorId);
    }

    private void handleMeterValues(WebSocketSession session, String msgId, JsonNode payload) throws IOException {
        String chargerId = extractChargerId(session);
        int connectorId = payload.path("connectorId").asInt(0);
        int transactionId = payload.path("transactionId").asInt(-1);
        if (transactionId < 0) {
            sendCallResult(session, msgId, Map.of());
            return;
        }

        ChargingSession chargingSession = chargingSessionRepository.findByOcppTransactionId(transactionId)
                .orElse(null);

        if (chargingSession == null) {
            logger.warn("MeterValues: no session found for chargerId={} connectorId={} transactionId={}, sending empty CallResult",
                chargerId, connectorId, transactionId);
            sendCallResult(session, msgId, Map.of());

            // Immediate stop attempt for stale transaction resuming after offline recovery.
            try {
                boolean stopped = sendRemoteStop(chargerId, transactionId);
                recordConnectorEvent("STALE_TX_REMOTE_STOP", chargerId, connectorId, stopped ? "ACCEPTED" : "REJECTED",
                        null, transactionId,
                        "RemoteStop attempted for unknown transaction after reconnect");
            } catch (Exception ex) {
                recordConnectorEvent("STALE_TX_REMOTE_STOP", chargerId, connectorId, "FAILED",
                        null, transactionId,
                        "RemoteStop failed for unknown transaction: " + ex.getMessage());
            }

            // Charger may have resumed old transaction after reconnect/power restore.
            // Best-effort stop to prevent physical charging without an active CMS session.
            mitigateStaleChargingWithoutSession(chargerId, connectorId <= 0 ? 0 : connectorId);
            return;
        }

        if ("COMPLETED".equalsIgnoreCase(chargingSession.getStatus())
            || "FAILED".equalsIgnoreCase(chargingSession.getStatus())) {
            logger.info("Ignoring MeterValues for finalized sessionId={} transactionId={}",
                chargingSession.getId(), transactionId);
            sendCallResult(session, msgId, Map.of());
            return;
        }

        JsonNode sampledValues = payload.path("sampledValue");
        if (!sampledValues.isArray() && payload.path("meterValue").isArray() && payload.path("meterValue").size() > 0) {
            sampledValues = payload.path("meterValue").get(0).path("sampledValue");
        }
        long energyWh = extractSampledLong(sampledValues, "Energy.Active.Import.Register", "Wh");
        double powerW = extractSampledDouble(sampledValues, "Power.Active.Import", "W");
        double voltageV = extractSampledDouble(sampledValues, "Voltage", "V");
        double currentA = extractSampledDouble(sampledValues, "Current.Import", "A");
        double socValue = extractSampledDouble(sampledValues, "SoC", "Percent");

        // When the charger reports Power.Active.Import=0 at summary level but has per-phase
        // current/voltage data, compute power from phase data as fallback.
        if (powerW <= 0.0) {
            double l1V = extractSampledDoubleByPhase(sampledValues, "Voltage", "V", "L1-N");
            double l2V = extractSampledDoubleByPhase(sampledValues, "Voltage", "V", "L2-N");
            double l3V = extractSampledDoubleByPhase(sampledValues, "Voltage", "V", "L3-N");
            double l1A = extractSampledDoubleByPhase(sampledValues, "Current.Import", "A", "L1-N");
            double l2A = extractSampledDoubleByPhase(sampledValues, "Current.Import", "A", "L2-N");
            double l3A = extractSampledDoubleByPhase(sampledValues, "Current.Import", "A", "L3-N");
            double phasePower = (l1V * l1A) + (l2V * l2A) + (l3V * l3A);
            if (phasePower > 0.0) {
                powerW = phasePower;
                logger.debug("MeterValues: Power.Active.Import=0, computed phase power={}W", String.format("%.2f", powerW));
            }
        }

        if ("STOPPING".equalsIgnoreCase(chargingSession.getStatus())) {
            logger.info("MeterValues received after STOPPING for sessionId={}, transactionId={}; completing session from latest meter",
                chargingSession.getId(), transactionId);
            // ACK first so charger doesn't disconnect, then complete
            sendCallResult(session, msgId, Map.of());
            completeSessionFromMeter(chargingSession, energyWh, "STOPPING_METER_FALLBACK", transactionId);
            return;
        }

        MeterValue meterValue = new MeterValue();
        meterValue.setSession(chargingSession);
        meterValue.setTimestamp(LocalDateTime.now());
        meterValue.setEnergyWh(energyWh);
        meterValue.setPowerW(powerW);
        meterValue.setVoltageV(voltageV);
        meterValue.setCurrentA(currentA);
        meterValueRepository.save(meterValue);

        if (socValue > 0) {
            final double boundedSoc = Math.max(0.0, Math.min(100.0, socValue));
            latestSocByTransaction.merge(transactionId, boundedSoc, Math::max);
        }

        updateEnergyProgress(chargingSession, transactionId, energyWh);

        // CRITICAL: ACK the MeterValues message to the charger BEFORE sending RemoteStopTransaction.
        // Without this, the charger waits for the CallResult of MeterValues while we wait for
        // its Accepted on RemoteStop → deadlock → WebSocket disconnect.
        sendCallResult(session, msgId, Map.of());

        // Now safe to send RemoteStop or Reset — charger is free to process new commands.
        enforceAutoStopLimit(chargingSession, transactionId, energyWh);
        enforceAcNoEnergyTimeout(chargingSession, transactionId, energyWh, null);

        if (socValue >= 100.0 && "ACTIVE".equalsIgnoreCase(chargingSession.getStatus())) {
            logger.info("Auto-stop triggered for full battery sessionId={}, SoC={}", chargingSession.getId(), socValue);
            boolean stopSent = chargingSession.getCharger() != null
                && sendRemoteStop(chargingSession.getCharger().getOcppIdentity(), transactionId);
            if (stopSent) {
                chargingSession.setStatus("STOPPING");
                chargingSessionRepository.save(chargingSession);
            } else {
                completeSessionFromMeter(chargingSession, energyWh, "BATTERY_FULL", transactionId);
            }
        }
    }

    private void enforceAutoStopLimit(ChargingSession chargingSession, int transactionId, long currentMeterWh) {
        if (!"ACTIVE".equalsIgnoreCase(chargingSession.getStatus())
                || chargingSession.getLimitType() == null
                || chargingSession.getLimitValue() == null) {
            return;
        }

        String limitType = chargingSession.getLimitType().toUpperCase();
        double limitValue = chargingSession.getLimitValue();
        long meterStart = chargingSession.getMeterStart() == null ? 0L : chargingSession.getMeterStart();
        double energyKwh = Math.max(0.0, (currentMeterWh - meterStart) / 1000.0);

        boolean shouldStop = false;
        String reason = "LIMIT_" + limitType;

        switch (limitType) {
            case "ENERGY" -> shouldStop = energyKwh >= limitValue;
            case "TIME" -> {
                if (chargingSession.getStartedAt() != null) {
                    long elapsedMinutes = ChronoUnit.MINUTES.between(chargingSession.getStartedAt(), LocalDateTime.now());
                    shouldStop = elapsedMinutes >= limitValue;
                }
            }
            case "AMOUNT" -> {
                Charger charger = chargingSession.getCharger();
                if (charger == null || charger.getStation() == null) {
                    return;
                }

                Tariff tariff = tariffRepository.findByStation_Id(charger.getStation().getId())
                        .orElse(null);
                if (tariff == null) {
                    return;
                }

                double projectedTotal = calculateTotalWithCharges(energyKwh, tariff);
                double effectiveBudget = limitValue;
                if (chargingSession.getPreauthAmount() != null && chargingSession.getPreauthAmount() > 0) {
                    effectiveBudget = Math.min(effectiveBudget, chargingSession.getPreauthAmount());
                }
                shouldStop = projectedTotal >= effectiveBudget;
            }
            default -> {
                return;
            }
        }

        if (!shouldStop) {
            return;
        }

        Charger charger = chargingSession.getCharger();
        logger.info("Auto-stop triggered sessionId={}, limitType={}, transactionId={}",
                chargingSession.getId(), limitType, transactionId);

        boolean stopSent = charger != null && sendRemoteStop(charger.getOcppIdentity(), transactionId);
        if (stopSent) {
            chargingSession.setStatus("STOPPING");
            chargingSessionRepository.save(chargingSession);
            recordConnectorEvent("AUTO_STOP_SENT", charger.getOcppIdentity(), chargingSession.getConnectorNo(), "STOPPING",
                    chargingSession.getId(), transactionId, "Auto-stop triggered due to " + reason);
            return;
        }

        // If we cannot send remote stop (charger offline), force finalize to protect budget cap.
        completeSessionFromMeter(chargingSession, currentMeterWh, reason, transactionId);
    }

    private void handleStopTransaction(WebSocketSession session, String msgId, JsonNode payload) throws IOException {
        int transactionId = payload.path("transactionId").asInt(-1);
        long meterStop = payload.path("meterStop").asLong(0L);
        String reason = payload.path("reason").asText("Unknown");
        logger.info("StopTransaction received transactionId={}, meterStop={}, reason={}", transactionId, meterStop, reason);

        ChargingSession chargingSession = chargingSessionRepository.findByOcppTransactionId(transactionId)
                .orElse(null);

        if (chargingSession == null) {
            logger.warn("StopTransaction: no session found for transactionId={}, sending empty CallResult", transactionId);
            sendCallResult(session, msgId, Map.of());
            return;
        }

        if ("COMPLETED".equalsIgnoreCase(chargingSession.getStatus())) {
            logger.info("StopTransaction received for already completed sessionId={}, transactionId={}",
                chargingSession.getId(), transactionId);
            sendCallResult(session, msgId, Map.of());
            return;
        }

        // Some chargers send meterStop=0 even though they reported a valid energy register
        // via MeterValues. Fall back to the latest stored MeterValue in that case.
        long meterStart = chargingSession.getMeterStart() == null ? 0L : chargingSession.getMeterStart();
        if (meterStop == 0 || meterStop < meterStart) {
            long latestWh = meterValueRepository.findLatestBySessionId(chargingSession.getId())
                    .map(MeterValue::getEnergyWh)
                    .filter(wh -> wh != null && wh > meterStart)
                    .orElse(meterStop);
            if (latestWh > meterStop) {
                logger.info("StopTransaction meterStop={} invalid (meterStart={}); using latest MeterValue={} for transactionId={}",
                        meterStop, meterStart, latestWh, transactionId);
                meterStop = latestWh;
            }
        }

        completeSessionFromMeter(chargingSession, meterStop, reason, transactionId);

        Charger charger = chargingSession.getCharger();
        logger.info("Session completed sessionId={}, chargerId={}, connectorId={}, transactionId={}, energyKwh={}",
            chargingSession.getId(), charger.getOcppIdentity(), chargingSession.getConnectorNo(), transactionId, chargingSession.getEnergyConsumedKwh());
        recordConnectorEvent("STOP_TRANSACTION", charger.getOcppIdentity(), chargingSession.getConnectorNo(), "COMPLETED",
            chargingSession.getId(), transactionId,
            "StopTransaction processed, reason=" + reason + ", energyKwh=" + chargingSession.getEnergyConsumedKwh());

        sendCallResult(session, msgId, Map.of());
    }

    private void completeSessionFromMeter(ChargingSession chargingSession, long meterStop, String reason, Integer transactionId) {
        if ("COMPLETED".equalsIgnoreCase(chargingSession.getStatus())) {
            return;
        }

        Charger charger = chargingSession.getCharger();
        Tariff tariff = tariffRepository.findByStation_Id(charger.getStation().getId())
                .orElseThrow(() -> new IllegalStateException("Tariff not found for stationId=" + charger.getStation().getId()));

        long meterStart = chargingSession.getMeterStart() == null ? 0L : chargingSession.getMeterStart();
        double energyKwh = Math.max(0.0, (meterStop - meterStart) / 1000.0);
        double energyAmount = energyKwh * tariff.getPricePerKwh();
        double uncappedTotal = roundCurrency(energyAmount * (1.0 + GST_PERCENT / 100.0));
        double effectiveCap = resolveEffectiveAmountCap(chargingSession);
        double total = roundCurrency(Math.min(uncappedTotal, effectiveCap));
        double gst = roundCurrency(total * GST_PERCENT / (100.0 + GST_PERCENT));
        double base = roundCurrency(total - gst);
        double platformFeePercent = resolvePlatformFeePercent(tariff);
        double platformFee = roundCurrency(base * (platformFeePercent / 100.0));
        double ownerRevenue = roundCurrency(base - platformFee);

        chargingSession.setMeterStop(meterStop);
        chargingSession.setEnergyConsumedKwh(energyKwh);
        chargingSession.setBaseAmount(base);
        chargingSession.setGstAmount(gst);
        chargingSession.setTotalAmount(total);
        chargingSession.setPlatformFee(platformFee);
        chargingSession.setOwnerRevenue(ownerRevenue);
        chargingSession.setChargerId(charger.getId());
        chargingSession.setStationId(charger.getStationId());
        chargingSession.setOwnerId(charger.getOwnerId());
        settlePaymentAndRefund(chargingSession);
        generateInvoice(chargingSession);
        chargingSession.setStatus("COMPLETED");
        chargingSession.setEndedAt(LocalDateTime.now());
        chargingSessionRepository.save(chargingSession);

        msg91OtpService.sendChargeCompleteMessage(chargingSession.getPhoneNumber());

        connectorRepository.findByCharger_IdAndConnectorNo(charger.getId(), chargingSession.getConnectorNo())
                .ifPresent(connector -> {
                    connector.setStatus("Available");
                    connectorRepository.save(connector);
                });

        charger.setStatus("Available");
        chargerRepository.save(charger);

        if (transactionId != null) {
            recordConnectorEvent("SESSION_COMPLETED", charger.getOcppIdentity(), chargingSession.getConnectorNo(), "COMPLETED",
                    chargingSession.getId(), transactionId,
                    "Session completed reason=" + reason + ", total=" + total);
            clearTransactionTracking(transactionId);
        }
    }

    private void enforceAcNoEnergyTimeoutOnHeartbeat(String chargerId) {
        chargingSessionRepository
                .findFirstByCharger_OcppIdentityAndStatusInOrderByCreatedAtDesc(chargerId, Set.of("ACTIVE"))
                .ifPresent(session -> enforceAcNoEnergyTimeout(session, session.getOcppTransactionId(), null, chargerId));
    }

    private void enforceAcNoEnergyTimeout(ChargingSession session, Integer transactionId, Long currentMeterWh, String chargerIdFromHeartbeat) {
        if (session == null || !"ACTIVE".equalsIgnoreCase(session.getStatus())) {
            return;
        }

        if (!isAcOrSocketSession(session)) {
            return;
        }

        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        LocalDateTime now = LocalDateTime.now();

        long latestEnergyWh = currentMeterWh != null ? currentMeterWh : meterValueRepository.findLatestBySessionId(session.getId())
                .map(MeterValue::getEnergyWh)
                .orElse(meterStart);

        if (transactionId != null) {
            updateEnergyProgress(session, transactionId, latestEnergyWh);
        }

        LocalDateTime progressAt = null;
        if (transactionId != null) {
            progressAt = lastEnergyProgressAtByTransaction.get(transactionId);
        }
        if (progressAt == null) {
            progressAt = session.getStartedAt() != null ? session.getStartedAt() : now;
        }

        long idleSeconds = Duration.between(progressAt, now).getSeconds();
        if (idleSeconds < Math.max(15, acNoEnergyTimeoutSeconds)) {
            return;
        }

        String chargerIdentity = resolveChargerIdentity(session, chargerIdFromHeartbeat);
        String reason = latestEnergyWh <= meterStart ? "AC_NO_ENERGY_AT_START" : "AC_NO_ENERGY_TIMEOUT";

        logger.info(
                "Auto-stopping AC/sessionId={} tx={} reason={} idle={}s meterStart={} latestEnergy={}",
                session.getId(), transactionId, reason, idleSeconds, meterStart, latestEnergyWh
        );

        if (chargerIdentity != null && transactionId != null) {
            try {
                sendRemoteStop(chargerIdentity, transactionId);
            } catch (Exception ex) {
                logger.debug("RemoteStop best-effort failed for sessionId={} tx={}: {}",
                        session.getId(), transactionId, ex.getMessage());
            }
        }

        completeSessionFromMeter(session, latestEnergyWh, reason, transactionId);

        recordConnectorEvent(
                "AUTO_STOP_NO_ENERGY",
                chargerIdentity,
                session.getConnectorNo(),
                "COMPLETED",
                session.getId(),
                transactionId,
                "Auto-stop due to no energy progress for " + idleSeconds + "s"
        );
    }

    private void updateEnergyProgress(ChargingSession session, Integer transactionId, long currentMeterWh) {
        if (transactionId == null) {
            return;
        }

        long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
        Long previous = latestEnergyWhByTransaction.put(transactionId, currentMeterWh);
        LocalDateTime now = LocalDateTime.now();

        if (previous == null) {
            LocalDateTime baseline = session.getStartedAt() != null ? session.getStartedAt() : now;
            if (currentMeterWh > meterStart) {
                lastEnergyProgressAtByTransaction.put(transactionId, now);
            } else {
                lastEnergyProgressAtByTransaction.put(transactionId, baseline);
            }
            return;
        }

        if (currentMeterWh > previous) {
            lastEnergyProgressAtByTransaction.put(transactionId, now);
        }
    }

    private boolean isAcOrSocketSession(ChargingSession session) {
        Long sessionId = session.getId();
        if (sessionId == null) {
            return false;
        }

        String chargerType = chargingSessionRepository.findChargerTypeBySessionId(sessionId)
                .map(value -> value == null ? "" : value.toUpperCase())
                .orElse("");
        String connectorType = chargingSessionRepository.findConnectorTypeBySessionId(sessionId)
                .map(value -> value == null ? "" : value.toUpperCase())
                .orElse("");

        if (chargerType.contains("AC") || connectorType.contains("AC") || connectorType.contains("SOCKET")) {
            return true;
        }

        return connectorType.contains("TYPE2") || connectorType.contains("TYPE 2")
                || connectorType.contains("TYPE1") || connectorType.contains("TYPE 1");
    }

    private String resolveChargerIdentity(ChargingSession session, String fallbackChargerId) {
        if (session.getId() != null) {
            Optional<String> chargerIdentity = chargingSessionRepository.findChargerOcppIdentityBySessionId(session.getId());
            if (chargerIdentity.isPresent()) {
                return chargerIdentity.get();
            }
        }
        return fallbackChargerId;
    }

    private void clearTransactionTracking(Integer transactionId) {
        latestSocByTransaction.remove(transactionId);
        latestEnergyWhByTransaction.remove(transactionId);
        lastEnergyProgressAtByTransaction.remove(transactionId);
    }

    private double calculateTotalWithCharges(double energyKwh, Tariff tariff) {
        double base = Math.max(0.0, energyKwh) * tariff.getPricePerKwh();
        return roundCurrency(base * (1.0 + GST_PERCENT / 100.0));
    }

    private void settlePaymentAndRefund(ChargingSession session) {
        String preauthId = session.getPreauthId();
        Double preauthAmount = session.getPreauthAmount();
        Double totalAmount = session.getTotalAmount();

        if (preauthId == null || preauthId.isBlank() || preauthAmount == null || totalAmount == null) {
            return;
        }

        try {
            paymentService.capture(preauthId, BigDecimal.valueOf(totalAmount));
            session.setPaymentStatus("CAPTURED");

            double unusedAmount = roundCurrency(Math.max(0.0, preauthAmount - totalAmount));
            if (unusedAmount > 0.01) {
                PaymentService.RefundResult refundResult = paymentService.refund(
                        preauthId,
                        BigDecimal.valueOf(unusedAmount)
                );
                if (refundResult.success()) {
                    session.setRefundAmount(unusedAmount);
                    session.setRefundId(refundResult.refundId());
                }
            } else {
                session.setRefundAmount(0.0);
                session.setRefundId(null);
            }
        } catch (Exception ex) {
            logger.error("Payment settlement failed for session {} in OCPP completion: {}",
                    session.getId(), ex.getMessage(), ex);
            session.setPaymentStatus("SETTLEMENT_FAILED");
        }
    }

    private void generateInvoice(ChargingSession session) {
        try {
            LocalDateTime endedAt = session.getEndedAt() != null ? session.getEndedAt() : LocalDateTime.now();
            String month = endedAt.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
            String invoiceNumber = String.format("INV-%s-%06d", month, session.getId());
            session.setInvoiceNumber(invoiceNumber);
            session.setInvoiceUrl(String.format("/api/sessions/%d/invoice", session.getId()));
        } catch (Exception ex) {
            logger.warn("Failed to generate invoice for session {}: {}", session.getId(), ex.getMessage());
        }
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

    private double roundCurrency(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.HALF_UP).doubleValue();
    }

    private double resolvePlatformFeePercent(Tariff tariff) {
        if (tariff == null || tariff.getPlatformFeePercent() == null) {
            return DEFAULT_PLATFORM_FEE_PERCENT;
        }
        return Math.max(0.0, tariff.getPlatformFeePercent());
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
                    // Parse as double first to handle decimal strings like "1026379.38"
                    double parsed = Double.parseDouble(sampledValue.path("value").asText("0"));
                    return Math.round(parsed);
                } catch (NumberFormatException ignored) {
                    return 0L;
                }
            }
        }

        return 0L;
    }

    /**
     * Extract a sampled double for a specific phase (e.g. "L1-N").
     * Returns 0.0 if not found.
     */
    private double extractSampledDoubleByPhase(JsonNode sampledValues, String measurand, String unit, String phase) {
        if (!sampledValues.isArray()) return 0.0;
        for (JsonNode sv : sampledValues) {
            if (measurand.equalsIgnoreCase(sv.path("measurand").asText(""))
                    && (unit.equalsIgnoreCase(sv.path("unit").asText("")) || sv.path("unit").asText("").isBlank())
                    && phase.equalsIgnoreCase(sv.path("phase").asText(""))) {
                try {
                    return Double.parseDouble(sv.path("value").asText("0"));
                } catch (NumberFormatException ignored) {
                    return 0.0;
                }
            }
        }
        return 0.0;
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

    private void updateChargerHeartbeat(String chargerId) {
        chargerRepository.findByOcppIdentity(chargerId).ifPresent(charger -> {
            charger.setLastHeartbeat(LocalDateTime.now());
            charger.setCommunicationStatus("ONLINE");
            chargerRepository.save(charger);
        });
    }

    private void sendCall(WebSocketSession session, String messageId, String action, Object payload) {
        try {
            String call = objectMapper.writeValueAsString(new Object[]{2, messageId, action, payload});
            session.sendMessage(new TextMessage(call));
            String chargerId = extractChargerId(session);
            persistOcppLog(chargerId, action, "OUTBOUND", call);
        } catch (IOException ex) {
            logger.warn("Failed to send OCPP CALL action={} messageId={}: {}", action, messageId, ex.getMessage());
        }
    }

    private JsonNode sendCommandAndAwait(String ocppIdentity, String prefix, String action, Map<String, Object> payload) {
        WebSocketSession session = activeSessions.get(ocppIdentity);
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("No active OCPP session found for charger " + ocppIdentity);
        }

        String messageId = prefix + "-" + UUID.randomUUID();
        CompletableFuture<JsonNode> responseFuture = new CompletableFuture<>();
        pendingResponses.put(messageId, responseFuture);
        sendCall(session, messageId, action, payload);

        try {
            return responseFuture.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new IllegalStateException(action + " timed out for charger " + ocppIdentity, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(action + " interrupted for charger " + ocppIdentity, ex);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            throw new IllegalStateException(action + " failed for charger " + ocppIdentity + ": " + cause.getMessage(), cause);
        } finally {
            pendingResponses.remove(messageId);
        }
    }

    private void completePendingResponse(String messageId, JsonNode payload, JsonNode errorPayload) {
        CompletableFuture<JsonNode> responseFuture = pendingResponses.remove(messageId);
        if (responseFuture == null) {
            return;
        }

        if (errorPayload != null && !errorPayload.isMissingNode() && !errorPayload.isNull()) {
            responseFuture.completeExceptionally(new IllegalStateException(errorPayload.toString()));
            return;
        }

        responseFuture.complete(payload == null ? objectMapper.createObjectNode() : payload);
    }

    private void sendCallResult(WebSocketSession session, String msgId, Object payload) throws IOException {
        String callResult = objectMapper.writeValueAsString(new Object[]{3, msgId, payload});
        session.sendMessage(new TextMessage(callResult));
        String chargerId = extractChargerId(session);
        persistOcppLog(chargerId, "CALL_RESULT", "OUTBOUND", callResult);
    }

    private void logInboundMessage(String chargerId, int messageType, JsonNode root) {
        try {
            if (messageType == 2 && root.size() >= 4) {
                String action = root.path(2).asText("UNKNOWN");
                persistOcppLog(chargerId, action, "INBOUND", root.toString());
                return;
            }

            if (messageType == 3) {
                persistOcppLog(chargerId, "CALL_RESULT", "INBOUND", root.toString());
                return;
            }

            if (messageType == 4) {
                persistOcppLog(chargerId, "CALL_ERROR", "INBOUND", root.toString());
            }
        } catch (Exception ex) {
            logger.debug("Skipping OCPP inbound log persist: {}", ex.getMessage());
        }
    }

    private void persistOcppLog(String chargerId, String action, String direction, String payloadJson) {
        try {
            OcppMessageLog log = new OcppMessageLog();
            log.setChargePointIdentity(chargerId);
            log.setAction(action == null || action.isBlank() ? "UNKNOWN" : action);
            log.setDirection(direction);
            log.setPayloadJson(payloadJson);

            chargerRepository.findByOcppIdentity(chargerId).ifPresent(charger -> {
                log.setStationId(charger.getStationId());
            });

            ocppMessageLogRepository.save(log);
        } catch (Exception ex) {
            logger.debug("Failed to persist OCPP message log for chargerId={}: {}", chargerId, ex.getMessage());
        }
    }

    private String extractChargerId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null || uri.getPath() == null || uri.getPath().isBlank()) {
            return "UNKNOWN";
        }

        String[] segments = uri.getPath().split("/");
        return segments.length == 0 ? "UNKNOWN" : segments[segments.length - 1];
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    public Double getLatestSoc(Integer transactionId) {
        if (transactionId == null) return null;
        return latestSocByTransaction.get(transactionId);
    }

    public void clearSocForTransaction(Integer transactionId) {
        if (transactionId != null) latestSocByTransaction.remove(transactionId);
    }

    public boolean isChargerConnected(String ocppIdentity) {
        WebSocketSession session = activeSessions.get(ocppIdentity);
        if (session == null || !session.isOpen()) {
            return false;
        }
        long offlineThresholdSeconds = resolveOfflineThresholdSeconds(ocppIdentity);
        return chargerRepository.findByOcppIdentity(ocppIdentity)
                .map(charger -> {
                    LocalDateTime lastHb = charger.getLastHeartbeat();
                    if (lastHb == null) return false;
                    return lastHb.isAfter(LocalDateTime.now().minusSeconds(offlineThresholdSeconds));
                })
                .orElse(false);
    }

    public long resolveOfflineThresholdSeconds(String ocppIdentity) {
        long configuredHeartbeat = ocppConfigurationRepository.findByChargePointIdentity(ocppIdentity)
                .map(OcppConfiguration::getHeartbeatIntervalSeconds)
                .filter(value -> value != null && value > 0)
                .map(Integer::longValue)
                .orElse(0L);

        long dynamicThreshold = configuredHeartbeat > 0
                ? configuredHeartbeat * Math.max(2, heartbeatMissMultiplier)
                : 0L;

        return Math.max(Math.max(30, minimumOfflineThresholdSeconds), dynamicThreshold);
    }

    public List<Map<String, Object>> getRecentConnectorEvents(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_RECENT_EVENTS));
        List<Map<String, Object>> snapshot = new ArrayList<>(safeLimit);
        int count = 0;
        for (Map<String, Object> event : recentConnectorEvents) {
            snapshot.add(event);
            count++;
            if (count >= safeLimit) {
                break;
            }
        }
        return snapshot;
    }

    private void recordConnectorEvent(
            String eventType,
            String chargerId,
            Integer connectorId,
            String status,
            Long sessionId,
            Integer transactionId,
            String message
    ) {
        Map<String, Object> event = new java.util.LinkedHashMap<>();
        event.put("timestamp", LocalDateTime.now().toString());
        event.put("eventType", eventType);
        event.put("chargerId", chargerId);
        event.put("connectorId", connectorId);
        event.put("status", status);
        event.put("sessionId", sessionId);
        event.put("transactionId", transactionId);
        event.put("message", message);
        recentConnectorEvents.addFirst(event);
        while (recentConnectorEvents.size() > MAX_RECENT_EVENTS) {
            recentConnectorEvents.pollLast();
        }
    }

    private void recordUnknownConnectorAlert(String chargerId, int connectorId, String status, String errorCode) {
        Deque<UnknownConnectorAlert> alerts = unknownConnectorAlerts.computeIfAbsent(
                chargerId,
                ignored -> new ConcurrentLinkedDeque<>()
        );
        alerts.addFirst(new UnknownConnectorAlert(
                LocalDateTime.now().toString(),
                chargerId,
                connectorId,
                status,
                errorCode,
                "Charger sent StatusNotification for an unknown connectorId"
        ));
        while (alerts.size() > MAX_UNKNOWN_ALERTS_PER_CHARGER) {
            alerts.pollLast();
        }
    }

    public List<UnknownConnectorAlert> getUnknownConnectorAlerts(String ocppIdentity) {
        Deque<UnknownConnectorAlert> alerts = unknownConnectorAlerts.get(ocppIdentity);
        if (alerts == null || alerts.isEmpty()) {
            return List.of();
        }
        return alerts.stream().collect(Collectors.toList());
    }

    public void clearUnknownConnectorAlerts(String ocppIdentity) {
        unknownConnectorAlerts.remove(ocppIdentity);
    }

    public record UnknownConnectorAlert(
            String timestamp,
            String chargerId,
            Integer connectorId,
            String status,
            String errorCode,
            String message
    ) {
    }

    public record OcppCommandResult(
            String status,
            JsonNode payload,
            String action
    ) {
        public boolean isAccepted() {
            return "Accepted".equalsIgnoreCase(status)
                    || "Unlocked".equalsIgnoreCase(status)
                    || "Scheduled".equalsIgnoreCase(status);
        }
    }
}
