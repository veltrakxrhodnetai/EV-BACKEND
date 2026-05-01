package com.evcsms.backend.websocket;

import com.evcsms.backend.ocpp.OcppServerCoreWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Component
public class OcppWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(OcppWebSocketHandler.class);
    private static final String ATTR_SESSION_ID = "sessionId";

    private final OcppServerCoreWrapper ocppServerCoreWrapper;

    public OcppWebSocketHandler(OcppServerCoreWrapper ocppServerCoreWrapper) {
        this.ocppServerCoreWrapper = ocppServerCoreWrapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put(ATTR_SESSION_ID, session.getId());
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
        ocppServerCoreWrapper.clearSessionMapping(session.getId());
        logger.info("OCPP connection closed: sessionId={}, status={}", session.getId(), status);
    }
}
