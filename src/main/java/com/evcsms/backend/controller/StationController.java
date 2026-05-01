package com.evcsms.backend.controller;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.Connector;
import com.evcsms.backend.model.Station;
import com.evcsms.backend.model.Tariff;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.StationRepository;
import com.evcsms.backend.repository.TariffRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/stations")
@CrossOrigin(origins = "*")
public class StationController {

    private final StationRepository stationRepository;
    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;
    private final TariffRepository tariffRepository;

    public StationController(
            StationRepository stationRepository,
            ChargerRepository chargerRepository,
            ConnectorRepository connectorRepository,
            TariffRepository tariffRepository
    ) {
        this.stationRepository = stationRepository;
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
        this.tariffRepository = tariffRepository;
    }

    @GetMapping
    public List<StationSummaryResponse> getStations() {
        return stationRepository.findAll().stream()
                .map(station -> {
                    List<Charger> chargers = chargerRepository.findByStation_Id(station.getId());
                    int totalChargers = chargers.size();
                    int availableConnectors = chargers.stream()
                            .flatMap(charger -> connectorRepository.findByCharger_Id(charger.getId()).stream())
                            .map(Connector::getStatus)
                            .filter("Available"::equalsIgnoreCase)
                            .toList()
                            .size();

                    return new StationSummaryResponse(
                            station.getId(),
                            station.getName(),
                            station.getAddress(),
                            station.getCity(),
                            station.getState(),
                            station.getLatitude(),
                            station.getLongitude(),
                            station.getMapEmbedHtml(),
                            station.getStatus(),
                            totalChargers,
                            availableConnectors
                    );
                })
                .collect(Collectors.toList());
    }

    @GetMapping("/{stationId}")
    public StationDetailResponse getStation(@PathVariable Long stationId) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found: " + stationId));

        List<ChargerWithConnectorsResponse> chargers = chargerRepository.findByStation_Id(stationId).stream()
                .map(charger -> new ChargerWithConnectorsResponse(
                        charger.getId(),
                        charger.getOcppIdentity(),
                        charger.getName(),
                        charger.getStatus(),
                        charger.getMaxPowerKw(),
                        connectorRepository.findByCharger_Id(charger.getId()).stream()
                                .map(connector -> new ConnectorResponse(
                                        connector.getId(),
                                        connector.getConnectorNo(),
                                        connector.getType(),
                                        connector.getMaxPowerKw(),
                                        connector.getStatus()
                                ))
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        return new StationDetailResponse(
                station.getId(),
                station.getName(),
                station.getAddress(),
                station.getCity(),
                station.getState(),
                station.getLatitude(),
                station.getLongitude(),
                station.getMapEmbedHtml(),
                station.getStatus(),
                chargers
        );
    }

    @GetMapping("/{stationId}/chargers")
    public List<ChargerWithConnectorsResponse> getChargersByStation(@PathVariable Long stationId) {
        stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found: " + stationId));

        return chargerRepository.findByStation_Id(stationId).stream()
                .map(charger -> new ChargerWithConnectorsResponse(
                        charger.getId(),
                        charger.getOcppIdentity(),
                        charger.getName(),
                        charger.getStatus(),
                        charger.getMaxPowerKw(),
                        connectorRepository.findByCharger_Id(charger.getId()).stream()
                                .map(connector -> new ConnectorResponse(
                                        connector.getId(),
                                        connector.getConnectorNo(),
                                        connector.getType(),
                                        connector.getMaxPowerKw(),
                                        connector.getStatus()
                                ))
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
    }

    @GetMapping("/{stationId}/tariff")
    public TariffResponse getTariffByStation(@PathVariable Long stationId) {
        stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found: " + stationId));

        Tariff tariff = tariffRepository.findByStation_Id(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tariff not found for station: " + stationId));

        return new TariffResponse(
                tariff.getPricePerKwh(),
                tariff.getGstPercent(),
                tariff.getSessionFee(),
                tariff.getPlatformFeePercent(),
                tariff.getCurrency()
        );
    }

    public record StationSummaryResponse(
            Long id,
            String name,
            String address,
            String city,
            String state,
            Double latitude,
            Double longitude,
            String mapEmbedHtml,
            String status,
            int totalChargers,
            int availableConnectors
    ) {
    }

    public record StationDetailResponse(
            Long id,
            String name,
            String address,
            String city,
            String state,
            Double latitude,
            Double longitude,
            String mapEmbedHtml,
            String status,
            List<ChargerWithConnectorsResponse> chargers
    ) {
    }

    public record ChargerWithConnectorsResponse(
            Long id,
            String ocppIdentity,
            String name,
            String status,
            Double maxPowerKw,
            List<ConnectorResponse> connectors
    ) {
    }

    public record ConnectorResponse(
            Long id,
            Integer connectorNo,
            String type,
            Double maxPowerKw,
            String status
    ) {
    }

    public record TariffResponse(
            Double pricePerKwh,
            Double gstPercent,
            Double sessionFee,
            Double platformFeePercent,
            String currency
    ) {
    }
}
