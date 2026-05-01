package com.evcsms.backend.config;

import com.evcsms.backend.websocket.OcppWebSocketHandler;
import com.evcsms.backend.websocket.SessionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OcppWebSocketHandler ocppWebSocketHandler;
    private final SessionWebSocketHandler sessionWebSocketHandler;

    public WebSocketConfig(OcppWebSocketHandler ocppWebSocketHandler,
                           SessionWebSocketHandler sessionWebSocketHandler) {
        this.ocppWebSocketHandler = ocppWebSocketHandler;
        this.sessionWebSocketHandler = sessionWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(ocppWebSocketHandler, "/ocpp")
                .setAllowedOrigins("*");

        registry.addHandler(sessionWebSocketHandler, "/ws/sessions/{sessionId}")
                .setAllowedOrigins("*");

        // For development we allow all origins.
        // In production, replace "*" with an explicit allowlist of trusted frontend domains.
    }
}
