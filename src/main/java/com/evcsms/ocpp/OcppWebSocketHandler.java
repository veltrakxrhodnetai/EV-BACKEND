package com.evcsms.ocpp;

import com.evcsms.model.Charger;
import com.evcsms.repository.ChargerRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

@Component("ocppPathWebSocketHandler")
public class OcppWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(OcppWebSocketHandler.class);

    private final ObjectMapper objectMapper;
    private final ChargerRepository chargerRepository;

    public OcppWebSocketHandler(ObjectMapper objectMapper, ChargerRepository chargerRepository) {
        this.objectMapper = objectMapper;
        this.chargerRepository = chargerRepository;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String chargerId = extractChargerId(session);
        logger.info("Charger connected: {}", chargerId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        logger.info("{}", message.getPayload());

        JsonNode root = objectMapper.readTree(message.getPayload());
        if (!root.isArray() || root.size() < 4) {
            return;
        }

        int messageType = root.path(0).asInt(-1);
        String messageId = root.path(1).asText("");
        String action = root.path(2).asText("");
        JsonNode payload = root.path(3);

        if (messageType == 2 && "StatusNotification".equalsIgnoreCase(action)) {
            String chargerId = extractChargerId(session);
            String status = payload.path("status").asText("Unknown");

            Charger charger = chargerRepository.findByOcppIdentity(chargerId)
                    .orElseGet(() -> {
                        Charger created = new Charger();
                        created.setOcppIdentity(chargerId);
                        created.setName(chargerId);
                        created.setLocation("Unknown");
                        return created;
                    });
            charger.setStatus(status);
            chargerRepository.save(charger);

            logger.info("Charger {} status updated to {}", chargerId, status);
        }

        if (messageType == 2 && !messageId.isBlank()) {
            String callResult = objectMapper.writeValueAsString(new Object[]{3, messageId, Map.of()});
            session.sendMessage(new TextMessage(callResult));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        logger.info("Charger disconnected");
    }

    private String extractChargerId(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            return "UNKNOWN";
        }

        String path = uri.getPath();
        if (path == null || path.isBlank()) {
            return "UNKNOWN";
        }

        String[] segments = path.split("/");
        return segments.length == 0 ? "UNKNOWN" : segments[segments.length - 1];
    }
}
