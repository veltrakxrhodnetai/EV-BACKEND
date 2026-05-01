package com.evcsms.backend.config;

import com.evcsms.backend.ocpp.OcppServerCoreWrapper;
import com.evcsms.backend.service.OcppService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OcppServerConfig {

    @Bean
    public OcppServerCoreWrapper ocppServerCoreWrapper(ObjectMapper objectMapper, OcppService ocppService) {
        OcppServerCoreWrapper wrapper = new OcppServerCoreWrapper(objectMapper);

        // Java-OCA-OCPP core wiring example: register action handlers in Spring context.
        wrapper.registerActionHandler("BootNotification", ocppService::handleBootNotification);

        wrapper.registerActionHandler("Authorize", (chargerSerial, payload) ->
                ocppService.handleAuthorize(chargerSerial, payload.path("idTag").asText("UNKNOWN"))
        );

        wrapper.registerActionHandler("StartTransaction", ocppService::handleStartTransaction);
        wrapper.registerActionHandler("StopTransaction", ocppService::handleStopTransaction);
        wrapper.registerActionHandler("MeterValues", ocppService::handleMeterValues);

        // Mapping note: keep sessionId -> charger serial mapping in the wrapper so reconnects/messages
        // can be correlated even when individual OCPP payloads omit serial fields.
        return wrapper;
    }
}
