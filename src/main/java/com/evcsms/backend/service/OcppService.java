package com.evcsms.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OcppService {

    private static final Logger logger = LoggerFactory.getLogger(OcppService.class);
    private static final String DEFAULT_ID_TAG = "UNKNOWN";

    private final ChargingSessionService chargingSessionService;

    public OcppService(ChargingSessionService chargingSessionService) {
        this.chargingSessionService = chargingSessionService;
    }

    /**
     * Handles a generic incoming OCPP message payload and routes to domain handlers.
     *
     * @param sessionId active websocket session id
     * @param chargerId charger identifier
     * @param message raw OCPP message payload
     */
    public void handleIncomingMessage(String sessionId, String chargerId, JsonNode message) {
        String action = message.path("action").asText("");
        logger.info("Forwarded OCPP message: sessionId={}, chargerId={}, action={}", sessionId, chargerId, action);

        switch (action) {
            case "BootNotification" -> handleBootNotification(chargerId, message.path("payload"));
            case "Authorize" -> handleAuthorize(chargerId, message.path("payload").path("idTag").asText(DEFAULT_ID_TAG));
            case "StartTransaction" -> handleStartTransaction(chargerId, message.path("payload"));
            case "MeterValues" -> handleMeterValues(chargerId, message.path("payload"));
            case "StopTransaction" -> handleStopTransaction(chargerId, message.path("payload"));
            default -> logger.debug("No route for action '{}' in current OcppService", action);
        }
    }

    /**
     * Handles OCPP BootNotification sent by a charger when connecting.
     *
     * @param chargerId charger identifier
     * @param payload BootNotification payload
     */
    public void handleBootNotification(String chargerId, JsonNode payload) {
        logger.info("Handling BootNotification for chargerId={}", chargerId);
        chargingSessionService.registerChargerBoot(chargerId, payload);
        // DB update happens here: persist charger metadata, firmware, and last-seen timestamp.
    }

    /**
     * Handles OCPP Authorize request for a user/vehicle idTag.
     *
     * @param chargerId charger identifier
     * @param idTag authorization token from charger
     */
    public void handleAuthorize(String chargerId, String idTag) {
        logger.info("Handling Authorize for chargerId={}, idTag={}", chargerId, idTag);
        chargingSessionService.authorizeIdTag(chargerId, idTag);
        // DB update happens here: store authorization attempt and status for auditing.
    }

    /**
     * Handles OCPP StartTransaction request and opens a charging session.
     *
     * @param chargerId charger identifier
     * @param payload StartTransaction payload
     */
    public void handleStartTransaction(String chargerId, JsonNode payload) {
        logger.info("Handling StartTransaction for chargerId={}", chargerId);
        chargingSessionService.startTransaction(chargerId, payload);
        // DB update happens here: create transaction/session row with meterStart and start time.
    }

    /**
     * Handles OCPP MeterValues request with periodic meter samples.
     *
     * @param chargerId charger identifier
     * @param payload MeterValues payload
     */
    public void handleMeterValues(String chargerId, JsonNode payload) {
        logger.debug("Handling MeterValues for chargerId={}", chargerId);
        chargingSessionService.saveMeterValues(chargerId, payload);
        // DB update happens here: append sampled meter values to time-series/transaction records.
    }

    /**
     * Handles OCPP StopTransaction request and closes a charging session.
     *
     * @param chargerId charger identifier
     * @param payload StopTransaction payload
     */
    public void handleStopTransaction(String chargerId, JsonNode payload) {
        logger.info("Handling StopTransaction for chargerId={}", chargerId);
        chargingSessionService.stopTransaction(chargerId, payload);
        // DB update happens here: finalize transaction with meterStop, end time, and status.
    }
}