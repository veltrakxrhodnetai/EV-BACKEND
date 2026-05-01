package com.evcsms.backend.controller;

import com.evcsms.backend.model.Connector;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController("backendChargerController")
@RequestMapping("/api/chargers")
@CrossOrigin(origins = "*")
public class ChargerController {

    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;

    public ChargerController(ChargerRepository chargerRepository, ConnectorRepository connectorRepository) {
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
    }

    @GetMapping("/{chargerId}/connectors")
    public List<ConnectorResponse> getConnectorsByCharger(@PathVariable Long chargerId) {
        chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found: " + chargerId));

        return connectorRepository.findByCharger_Id(chargerId).stream()
                .map(this::toResponse)
                .toList();
    }

    private ConnectorResponse toResponse(Connector connector) {
        return new ConnectorResponse(
                connector.getId(),
                connector.getConnectorNo(),
                connector.getType(),
                connector.getMaxPowerKw(),
                connector.getStatus()
        );
    }

    public record ConnectorResponse(
            Long id,
            Integer connectorNo,
            String type,
            Double maxPowerKw,
            String status
    ) {
    }
}
