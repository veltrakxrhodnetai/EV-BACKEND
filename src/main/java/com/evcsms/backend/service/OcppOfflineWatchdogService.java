package com.evcsms.backend.service;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.ChargingSession;
import com.evcsms.backend.model.Connector;
import com.evcsms.backend.model.MeterValue;
import com.evcsms.backend.ocpp.OcppWebSocketHandler;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.MeterValueRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class OcppOfflineWatchdogService {

    private static final Logger logger = LoggerFactory.getLogger(OcppOfflineWatchdogService.class);
    private static final Set<String> ACTIVE_SESSION_STATUSES = Set.of("ACTIVE", "STOPPING");

    private final ChargerRepository chargerRepository;
    private final ChargingSessionRepository chargingSessionRepository;
    private final ConnectorRepository connectorRepository;
    private final MeterValueRepository meterValueRepository;
    private final ChargingSessionService chargingSessionService;
    private final OcppWebSocketHandler ocppWebSocketHandler;

    @Value("${app.charging.offline-session-timeout-seconds:120}")
    private int offlineSessionTimeoutSeconds;

    public OcppOfflineWatchdogService(
            ChargerRepository chargerRepository,
            ChargingSessionRepository chargingSessionRepository,
            ConnectorRepository connectorRepository,
            MeterValueRepository meterValueRepository,
            ChargingSessionService chargingSessionService,
            OcppWebSocketHandler ocppWebSocketHandler
    ) {
        this.chargerRepository = chargerRepository;
        this.chargingSessionRepository = chargingSessionRepository;
        this.connectorRepository = connectorRepository;
        this.meterValueRepository = meterValueRepository;
        this.chargingSessionService = chargingSessionService;
        this.ocppWebSocketHandler = ocppWebSocketHandler;
    }

    @Scheduled(fixedDelayString = "${app.ocpp.presence.watchdog-interval-millis:15000}")
    @Transactional
    public void reconcileOfflineChargersAndSessions() {
        syncCommunicationStatus();
        completeOfflineSessions();
        mitigateStaleChargingWithoutSessions();
    }

    private void syncCommunicationStatus() {
        List<Charger> chargers = chargerRepository.findAll();
        List<Charger> changed = new ArrayList<>();

        for (Charger charger : chargers) {
            String expectedStatus = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity()) ? "ONLINE" : "OFFLINE";
            String currentStatus = charger.getCommunicationStatus() == null ? "OFFLINE" : charger.getCommunicationStatus().toUpperCase();
            if (!expectedStatus.equalsIgnoreCase(currentStatus)) {
                charger.setCommunicationStatus(expectedStatus);
                changed.add(charger);
            }
        }

        if (!changed.isEmpty()) {
            chargerRepository.saveAll(changed);
        }
    }

    private void completeOfflineSessions() {
        LocalDateTime now = LocalDateTime.now();
        List<ChargingSession> activeSessions = chargingSessionRepository.findByStatusInWithChargerOrderByCreatedAtDesc(ACTIVE_SESSION_STATUSES);

        for (ChargingSession session : activeSessions) {
            Charger charger = session.getCharger();
            if (charger == null || charger.getOcppIdentity() == null || ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity())) {
                continue;
            }

            LocalDateTime lastHeartbeat = charger.getLastHeartbeat();
            if (lastHeartbeat == null) {
                continue;
            }

            long offlineThresholdSeconds = ocppWebSocketHandler.resolveOfflineThresholdSeconds(charger.getOcppIdentity());
            long offlineSeconds = Duration.between(lastHeartbeat, now).getSeconds();
            if (offlineSeconds < Math.max(offlineThresholdSeconds, offlineSessionTimeoutSeconds)) {
                continue;
            }

            long meterStart = session.getMeterStart() == null ? 0L : session.getMeterStart();
            long latestMeterWh = meterValueRepository.findLatestBySessionId(session.getId())
                    .map(MeterValue::getEnergyWh)
                    .orElse(meterStart);

            logger.warn(
                    "Completing interrupted sessionId={} charger={} after offline timeout={}s latestMeterWh={}",
                    session.getId(),
                    charger.getOcppIdentity(),
                    offlineSeconds,
                    latestMeterWh
            );

            chargingSessionService.completeSessionImmediately(session.getId(), latestMeterWh);
        }
    }

    private void mitigateStaleChargingWithoutSessions() {
        List<Charger> chargers = chargerRepository.findAll();

        for (Charger charger : chargers) {
            if (charger.getOcppIdentity() == null || !ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity())) {
                continue;
            }

            boolean hasAnyActiveSession = chargingSessionRepository
                    .findFirstByCharger_OcppIdentityAndStatusInOrderByCreatedAtDesc(charger.getOcppIdentity(), ACTIVE_SESSION_STATUSES)
                    .isPresent();

            if ("CHARGING".equalsIgnoreCase(charger.getStatus()) && !hasAnyActiveSession) {
                logger.warn("Watchdog: charger {} reports CHARGING with no active session. Sending mitigation.",
                        charger.getOcppIdentity());
                ocppWebSocketHandler.enforceNoSessionCharging(charger.getOcppIdentity(), 0);
            }

            List<Connector> connectors = connectorRepository.findByCharger_Id(charger.getId());
            for (Connector connector : connectors) {
                if (!"CHARGING".equalsIgnoreCase(connector.getStatus())) {
                    continue;
                }

                boolean hasActiveSessionForConnector = chargingSessionRepository
                        .existsByCharger_IdAndConnectorNoAndStatusIn(charger.getId(), connector.getConnectorNo(), ACTIVE_SESSION_STATUSES);

                if (!hasActiveSessionForConnector) {
                    logger.warn("Watchdog: charger {} connector {} reports CHARGING with no active session. Sending mitigation.",
                            charger.getOcppIdentity(), connector.getConnectorNo());
                    ocppWebSocketHandler.enforceNoSessionCharging(charger.getOcppIdentity(), connector.getConnectorNo());
                }
            }
        }
    }
}