package com.evcsms.backend.config;

import com.evcsms.backend.websocket.SessionWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final SessionWebSocketHandler sessionWebSocketHandler;

    public WebSocketConfig(SessionWebSocketHandler sessionWebSocketHandler) {
        this.sessionWebSocketHandler = sessionWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // NOTE: /ws/ocpp/** routes are handled by com.evcsms.config.WebSocketConfig
        // using the full OcppWebSocketHandler (with activeSessions + ONLINE status tracking).
        // Do NOT re-register them here or the wrapper handler will win and chargers won't go ONLINE.

        registry.addHandler(sessionWebSocketHandler, "/ws/sessions/{sessionId}")
                .setAllowedOrigins("*");
    }
}
