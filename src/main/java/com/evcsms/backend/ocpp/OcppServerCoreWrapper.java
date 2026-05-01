package com.evcsms.backend.ocpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class OcppServerCoreWrapper {

    private static final Logger logger = LoggerFactory.getLogger(OcppServerCoreWrapper.class);
    private static final String ATTR_CHARGER_SERIAL = "chargerSerial";

    private final ObjectMapper objectMapper;
    private final Map<String, BiConsumer<String, JsonNode>> actionHandlers = new ConcurrentHashMap<>();
    private final Map<String, String> sessionIdToChargerSerial = new ConcurrentHashMap<>();

    public OcppServerCoreWrapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void registerActionHandler(String action, BiConsumer<String, JsonNode> handler) {
        actionHandlers.put(action, handler);
    }

    public void handleIncomingTextMessage(WebSocketSession session, TextMessage textMessage) throws IOException {
        JsonNode rawJson = objectMapper.readTree(textMessage.getPayload());

        // Example conversion point:
        // TODO Replace this wrapper conversion with Java-OCA-OCPP parser/request object creation.
        OcppLibraryRequest request = convertToLibraryRequest(rawJson, session);

        if (request.chargerSerial() != null && !request.chargerSerial().isBlank()) {
            sessionIdToChargerSerial.put(session.getId(), request.chargerSerial());
            // sessionId <-> charger serial mapping is maintained here for request correlation and auditing.
            session.getAttributes().put(ATTR_CHARGER_SERIAL, request.chargerSerial());
        }

        BiConsumer<String, JsonNode> handler = actionHandlers.get(request.action());
        if (handler == null) {
            logger.debug("No registered OCPP handler for action={}", request.action());
            return;
        }

        handler.accept(request.chargerSerial(), request.payload());
    }

    public void clearSessionMapping(String sessionId) {
        sessionIdToChargerSerial.remove(sessionId);
    }

    private OcppLibraryRequest convertToLibraryRequest(JsonNode rawJson, WebSocketSession session) {
        // Supports object style: {"action":"BootNotification","payload":{...}}
        String action = rawJson.path("action").asText("");
        JsonNode payload = rawJson.path("payload");

        // Supports OCPP-J array style: [2,"msgId","Action",{...}]
        if ((action == null || action.isBlank()) && rawJson.isArray() && rawJson.size() >= 4) {
            action = rawJson.get(2).asText("");
            payload = rawJson.get(3);
        }

        String mappedSerial = (String) session.getAttributes().get(ATTR_CHARGER_SERIAL);
        String chargerSerial = firstNonBlank(
                mappedSerial,
                rawJson.path("chargerSerial").asText(null),
                rawJson.path("chargerId").asText(null),
                payload.path("chargePointSerialNumber").asText(null),
                payload.path("chargeBoxSerialNumber").asText(null),
                payload.path("chargerId").asText(null)
        );

        return new OcppLibraryRequest(action, payload, chargerSerial);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record OcppLibraryRequest(String action, JsonNode payload, String chargerSerial) {
    }
}
