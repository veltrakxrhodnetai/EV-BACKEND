package com.evcsms.backend.controller;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.Connector;
import com.evcsms.backend.ocpp.OcppWebSocketHandler;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.ConnectorRepository;
import com.evcsms.backend.repository.StationSettlementRepository;
import com.evcsms.backend.service.OwnerAuthService;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/owner")
@CrossOrigin(origins = "*")
public class OwnerOperationsController {

    private static final Collection<String> ACTIVE_SESSION_STATUSES = List.of(
            "PENDING_VERIFICATION",
            "PENDING_PAYMENT",
            "PENDING_START",
            "ACTIVE",
            "STOPPING"
    );

    private final OwnerAuthService ownerAuthService;
    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;
    private final ChargingSessionRepository chargingSessionRepository;
    private final StationSettlementRepository stationSettlementRepository;
    private final OcppWebSocketHandler ocppWebSocketHandler;

    public OwnerOperationsController(
            OwnerAuthService ownerAuthService,
            ChargerRepository chargerRepository,
            ConnectorRepository connectorRepository,
            ChargingSessionRepository chargingSessionRepository,
                StationSettlementRepository stationSettlementRepository,
            OcppWebSocketHandler ocppWebSocketHandler
    ) {
        this.ownerAuthService = ownerAuthService;
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
        this.chargingSessionRepository = chargingSessionRepository;
            this.stationSettlementRepository = stationSettlementRepository;
        this.ocppWebSocketHandler = ocppWebSocketHandler;
    }

            @GetMapping("/financial-summary")
            public OwnerFinancialSummaryResponse getFinancialSummary(
                @RequestHeader("Authorization") String authorization
            ) {
            OwnerAuthService.AuthenticatedOwner owner = requireOwner(authorization);

            double totalOwnerRevenue = round2(nonNull(chargingSessionRepository.sumOwnerRevenueByOwnerId(owner.ownerId())));
            double totalSettled = round2(
                stationSettlementRepository.findByOwnerId(owner.ownerId()).stream()
                    .mapToDouble(item -> nonNull(item.getSettledAmount()))
                    .sum()
            );
            double totalUnsettled = round2(
                stationSettlementRepository.findByOwnerId(owner.ownerId()).stream()
                    .mapToDouble(item -> nonNull(item.getPendingAmount()))
                    .sum()
            );

            return new OwnerFinancialSummaryResponse(totalOwnerRevenue, totalSettled, totalUnsettled);
            }

    @GetMapping("/chargers")
    public List<OwnerChargerResponse> listOwnerChargers(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) Long stationId
    ) {
        OwnerAuthService.AuthenticatedOwner owner = requireOwner(authorization);

        List<Long> stationIds = owner.stationIds();
        if (stationId != null) {
            if (!owner.canAccessStation(stationId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: station is not assigned to owner");
            }
            stationIds = List.of(stationId);
        }

        List<OwnerChargerResponse> result = new ArrayList<>();
        for (Long assignedStationId : stationIds) {
            List<Charger> chargers = chargerRepository.findByStation_Id(assignedStationId);
            for (Charger charger : chargers) {
                List<OwnerConnectorResponse> connectors = connectorRepository.findByCharger_Id(charger.getId()).stream()
                        .map(connector -> new OwnerConnectorResponse(
                                connector.getId(),
                                connector.getConnectorNo(),
                                connector.getType(),
                                connector.getMaxPowerKw(),
                                connector.getStatus()
                        ))
                        .toList();

                result.add(new OwnerChargerResponse(
                        charger.getId(),
                        charger.getStationId(),
                        charger.getName(),
                        charger.getOcppIdentity(),
                        charger.getStatus(),
                        charger.getEnabled(),
                        charger.getCommunicationStatus(),
                        connectors
                ));
            }
        }

        return result;
    }

    @PatchMapping("/chargers/{chargerId}/enable")
    @Transactional
    public OwnerChargerResponse enableCharger(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId
    ) {
        OwnerAuthService.AuthenticatedOwner owner = requireOwner(authorization);
        Charger charger = requireAccessibleCharger(owner, chargerId);

        boolean online = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity());
        if (!online) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot enable");
        }

        OcppWebSocketHandler.OcppCommandResult result = executeChangeAvailability(charger, 0, "Operative");
        if (!result.isAccepted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ChangeAvailability rejected: " + result.status());
        }

        charger.setEnabled(true);
        charger.setStatus("AVAILABLE");
        charger = chargerRepository.save(charger);
        setAllConnectorsStatus(charger, "AVAILABLE");

        return toOwnerChargerResponse(charger);
    }

    @PatchMapping("/chargers/{chargerId}/disable")
    @Transactional
    public OwnerChargerResponse disableCharger(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId
    ) {
        OwnerAuthService.AuthenticatedOwner owner = requireOwner(authorization);
        Charger charger = requireAccessibleCharger(owner, chargerId);

        boolean online = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity());
        if (!online) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot disable");
        }

        OcppWebSocketHandler.OcppCommandResult result = executeChangeAvailability(charger, 0, "Inoperative");
        if (!result.isAccepted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ChangeAvailability rejected: " + result.status());
        }

        charger.setEnabled(false);
        charger.setStatus("UNAVAILABLE");
        charger = chargerRepository.save(charger);
        setAllConnectorsStatus(charger, "UNAVAILABLE");

        return toOwnerChargerResponse(charger);
    }

    @PostMapping("/chargers/{chargerId}/reset")
    public OwnerCommandResponse resetCharger(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId,
            @Valid @RequestBody OwnerChargerResetRequest request
    ) {
        OwnerAuthService.AuthenticatedOwner owner = requireOwner(authorization);
        Charger charger = requireAccessibleCharger(owner, chargerId);
        validateResetType(request.type());

        if (!ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot reset");
        }

        OcppWebSocketHandler.OcppCommandResult result = executeReset(charger, request.type());
        return new OwnerCommandResponse("Reset", result.status(), result.payload().toString());
    }

    @PatchMapping("/connectors/{connectorId}/availability")
    @Transactional
    public OwnerConnectorResponse setConnectorAvailability(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long connectorId,
            @RequestParam String status
    ) {
        OwnerAuthService.AuthenticatedOwner owner = requireOwner(authorization);
        Connector connector = requireAccessibleConnector(owner, connectorId);

        String desiredStatus = status == null ? "" : status.trim().toUpperCase();
        if (!"AVAILABLE".equals(desiredStatus) && !"UNAVAILABLE".equals(desiredStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be AVAILABLE or UNAVAILABLE");
        }

        if ("UNAVAILABLE".equals(desiredStatus)) {
            boolean hasActive = chargingSessionRepository.existsByCharger_IdAndConnectorNoAndStatusIn(
                    connector.getCharger().getId(),
                    connector.getConnectorNo(),
                    ACTIVE_SESSION_STATUSES
            );
            if (hasActive) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot mark connector as UNAVAILABLE: an active charging session is in progress");
            }
        }

        boolean online = ocppWebSocketHandler.isChargerConnected(connector.getCharger().getOcppIdentity());
        if (!online) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot change connector availability");
        }

        String ocppType = "AVAILABLE".equals(desiredStatus) ? "Operative" : "Inoperative";
        OcppWebSocketHandler.OcppCommandResult result = executeChangeAvailability(
                connector.getCharger(),
                connector.getConnectorNo(),
                ocppType
        );
        if (!result.isAccepted()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ChangeAvailability rejected: " + result.status());
        }

        connector.setStatus(desiredStatus);
        connector = connectorRepository.save(connector);

        return new OwnerConnectorResponse(
                connector.getId(),
                connector.getConnectorNo(),
                connector.getType(),
                connector.getMaxPowerKw(),
                connector.getStatus()
        );
    }

    private OwnerAuthService.AuthenticatedOwner requireOwner(String authorizationHeader) {
        try {
            return ownerAuthService.requireOwnerFromAuthorizationHeader(authorizationHeader);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    private Charger requireAccessibleCharger(OwnerAuthService.AuthenticatedOwner owner, Long chargerId) {
        Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

        Long stationId = charger.getStationId();
        if (stationId == null || !owner.canAccessStation(stationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: charger is not assigned to owner");
        }

        return charger;
    }

    private Connector requireAccessibleConnector(OwnerAuthService.AuthenticatedOwner owner, Long connectorId) {
        Connector connector = connectorRepository.findById(connectorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found"));

        Long stationId = connector.getCharger() == null ? null : connector.getCharger().getStationId();
        if (stationId == null || !owner.canAccessStation(stationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Access denied: connector is not assigned to owner");
        }

        return connector;
    }

    private OcppWebSocketHandler.OcppCommandResult executeChangeAvailability(Charger charger, int connectorId, String availabilityType) {
        try {
            return ocppWebSocketHandler.changeAvailability(charger.getOcppIdentity(), connectorId, availabilityType);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeReset(Charger charger, String resetType) {
        try {
            return ocppWebSocketHandler.reset(charger.getOcppIdentity(), resetType);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private void validateResetType(String type) {
        if (!"Hard".equalsIgnoreCase(type) && !"Soft".equalsIgnoreCase(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset type must be Hard or Soft");
        }
    }

    private void setAllConnectorsStatus(Charger charger, String status) {
        List<Connector> connectors = connectorRepository.findByCharger_Id(charger.getId());
        for (Connector connector : connectors) {
            connector.setStatus(status);
        }
        if (!connectors.isEmpty()) {
            connectorRepository.saveAll(connectors);
        }
    }

    private OwnerChargerResponse toOwnerChargerResponse(Charger charger) {
        List<OwnerConnectorResponse> connectors = connectorRepository.findByCharger_Id(charger.getId()).stream()
                .map(connector -> new OwnerConnectorResponse(
                        connector.getId(),
                        connector.getConnectorNo(),
                        connector.getType(),
                        connector.getMaxPowerKw(),
                        connector.getStatus()
                ))
                .toList();

        return new OwnerChargerResponse(
                charger.getId(),
                charger.getStationId(),
                charger.getName(),
                charger.getOcppIdentity(),
                charger.getStatus(),
                charger.getEnabled(),
                charger.getCommunicationStatus(),
                connectors
        );
    }

    public record OwnerChargerResetRequest(@NotBlank String type) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OwnerCommandResponse(String action, String status, String payload) {
    }

    public record OwnerConnectorResponse(
            Long id,
            Integer connectorNo,
            String type,
            Double maxPowerKw,
            String status
    ) {
    }

    public record OwnerChargerResponse(
            Long id,
            Long stationId,
            String name,
            String ocppIdentity,
            String status,
            Boolean enabled,
            String communicationStatus,
            List<OwnerConnectorResponse> connectors
    ) {
    }

    public record OwnerFinancialSummaryResponse(
            double totalOwnerRevenue,
            double totalSettled,
            double totalUnsettled
    ) {
    }

    private double nonNull(Double value) {
        return value == null ? 0.0 : value;
    }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
