package com.evcsms.config;

import com.evcsms.backend.ocpp.OcppWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration("ocppPathWebSocketConfig")
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final OcppWebSocketHandler ocppWebSocketHandler;

    public WebSocketConfig(OcppWebSocketHandler ocppWebSocketHandler) {
        this.ocppWebSocketHandler = ocppWebSocketHandler;
    }

    private DefaultHandshakeHandler ocppHandshakeHandler() {
        DefaultHandshakeHandler handler = new DefaultHandshakeHandler();
        handler.setSupportedProtocols("ocpp1.6", "ocpp1.5", "ocpp2.0", "ocpp2.0.1");
        return handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Production route: /ws/ocpp/{version}/{stationId}/{chargePointIdentity}
        registry.addHandler(ocppWebSocketHandler, "/ws/ocpp/{version}/{stationId}/{chargerId}")
            .setHandshakeHandler(ocppHandshakeHandler())
            .setAllowedOrigins("*");

        // Backward compatibility routes during migration.
        registry.addHandler(ocppWebSocketHandler, "/ocpp/{chargerId}")
            .setHandshakeHandler(ocppHandshakeHandler())
            .setAllowedOrigins("*");

        registry.addHandler(ocppWebSocketHandler, "/ws/ocpp/{chargerId}")
                .setHandshakeHandler(ocppHandshakeHandler())
                .setAllowedOrigins("*");
    }
}
