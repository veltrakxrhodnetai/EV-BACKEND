package com.evcsms.backend.websocket;

import com.evcsms.backend.ocpp.OcppServerCoreWrapper;
import com.evcsms.backend.service.ChargerUptimeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;

@Component
public class OcppWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(OcppWebSocketHandler.class);
    private static final String ATTR_SESSION_ID = "sessionId";
    private static final String ATTR_CHARGER_SERIAL = "chargerSerial";

    private final OcppServerCoreWrapper ocppServerCoreWrapper;
    private final ApplicationContext applicationContext;

    public OcppWebSocketHandler(OcppServerCoreWrapper ocppServerCoreWrapper,
                                ApplicationContext applicationContext) {
        this.ocppServerCoreWrapper = ocppServerCoreWrapper;
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(ATTR_SESSION_ID, session.getId());
        URI uri = session.getUri();
        if (uri != null) {
            String path = uri.getPath();
            String[] segments = path.split("/");
            if (segments.length > 0) {
                String identity = segments[segments.length - 1];
                if (identity != null && !identity.isBlank()) {
                    session.getAttributes().put(ATTR_CHARGER_SERIAL, identity);
                }
            }
        }
        logger.info("OCPP connection established: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        String payload = message.getPayload();

        if ("ping".equalsIgnoreCase(payload.trim())) {
            session.sendMessage(new TextMessage("pong"));
            logger.debug("Ping received and pong sent for sessionId={}", session.getId());
            return;
        }

        try {
            ocppServerCoreWrapper.handleIncomingTextMessage(session, message);
        } catch (IOException ex) {
            logger.warn("Invalid JSON received on OCPP sessionId={}: {}", session.getId(), ex.getMessage());
        }
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        logger.debug("Pong received from OCPP sessionId={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String chargerSerial = (String) session.getAttributes().get(ATTR_CHARGER_SERIAL);
        ocppServerCoreWrapper.clearSessionMapping(session.getId());
        logger.info("OCPP connection closed: sessionId={}, charger={}, status={}", session.getId(), chargerSerial, status);
        if (chargerSerial != null && !chargerSerial.isBlank()) {
            applicationContext.getBean(ChargerUptimeService.class).recordStatusChange(chargerSerial, "OFFLINE");
        }
    }
}
