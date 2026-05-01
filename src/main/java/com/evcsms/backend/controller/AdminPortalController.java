package com.evcsms.backend.controller;

import com.evcsms.backend.model.*;
import com.evcsms.backend.ocpp.OcppWebSocketHandler;
import com.evcsms.backend.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.evcsms.backend.service.AdminAuthService;
import com.evcsms.backend.service.SettlementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AdminPortalController {

    private final AdminAuthService adminAuthService;
    private final StationRepository stationRepository;
    private final ChargerRepository chargerRepository;
    private final ConnectorRepository connectorRepository;
    private final TariffRepository tariffRepository;
    private final ChargingSessionRepository chargingSessionRepository;
    private final OwnerAccountRepository ownerAccountRepository;
    private final OwnerStationAssignmentRepository ownerStationAssignmentRepository;
    private final CustomerRepository customerRepository;
    private final RfidRegistryRepository rfidRegistryRepository;
    private final OcppConfigurationRepository ocppConfigurationRepository;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final CompletedChargingLogRepository completedChargingLogRepository;
    private final SettlementRepository settlementRepository;
    private final StationSettlementRepository stationSettlementRepository;
    private final SettlementService settlementService;
    private final OcppWebSocketHandler ocppWebSocketHandler;
    private static final String CONNECTOR_STATUS_DELETED = "DELETED";

    public AdminPortalController(
            AdminAuthService adminAuthService,
            StationRepository stationRepository,
            ChargerRepository chargerRepository,
            ConnectorRepository connectorRepository,
            TariffRepository tariffRepository,
            ChargingSessionRepository chargingSessionRepository,
            OwnerAccountRepository ownerAccountRepository,
            OwnerStationAssignmentRepository ownerStationAssignmentRepository,
            CustomerRepository customerRepository,
            RfidRegistryRepository rfidRegistryRepository,
            OcppConfigurationRepository ocppConfigurationRepository,
                AdminAuditLogRepository adminAuditLogRepository,
                CompletedChargingLogRepository completedChargingLogRepository,
                SettlementRepository settlementRepository,
                StationSettlementRepository stationSettlementRepository,
                SettlementService settlementService,
                OcppWebSocketHandler ocppWebSocketHandler
    ) {
        this.adminAuthService = adminAuthService;
        this.stationRepository = stationRepository;
        this.chargerRepository = chargerRepository;
        this.connectorRepository = connectorRepository;
        this.tariffRepository = tariffRepository;
        this.chargingSessionRepository = chargingSessionRepository;
        this.ownerAccountRepository = ownerAccountRepository;
        this.ownerStationAssignmentRepository = ownerStationAssignmentRepository;
        this.customerRepository = customerRepository;
        this.rfidRegistryRepository = rfidRegistryRepository;
        this.ocppConfigurationRepository = ocppConfigurationRepository;
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.completedChargingLogRepository = completedChargingLogRepository;
        this.settlementRepository = settlementRepository;
        this.stationSettlementRepository = stationSettlementRepository;
        this.settlementService = settlementService;
        this.ocppWebSocketHandler = ocppWebSocketHandler;
    }

    @GetMapping("/dashboard/summary")
    public DashboardSummaryResponse getDashboardSummary(@RequestHeader("Authorization") String authorization) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        long totalStations = stationRepository.count();
        long totalChargers = chargerRepository.count();
        long onlineChargers = chargerRepository.countByCommunicationStatus("ONLINE");
        long offlineChargers = chargerRepository.countByCommunicationStatus("OFFLINE");
        long activeSessions = chargingSessionRepository.countByStatus("ACTIVE");

        LocalDateTime start = LocalDate.now().atStartOfDay();
        LocalDateTime end = start.plusDays(1).minusSeconds(1);
        double revenueToday = chargingSessionRepository.sumRevenueBetween(start, end);

        audit(admin.username(), "READ", "DASHBOARD", null, "{}");
        return new DashboardSummaryResponse(totalStations, totalChargers, onlineChargers, offlineChargers, activeSessions, revenueToday);
    }

        @GetMapping("/dashboard/financial")
        public FinancialDashboardResponse getFinancialDashboard(@RequestHeader("Authorization") String authorization) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "SUPER_ADMIN");

        List<ChargingSession> sessions = chargingSessionRepository.findCompletedFinancialSessions(List.of("PAID", "CAPTURED"));
        Map<Long, OwnerAccount> ownersById = ownerAccountRepository.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(OwnerAccount::getId, owner -> owner));
        Map<Long, StationSettlement> settlementsByStation = stationSettlementRepository.findAll().stream()
            .collect(java.util.stream.Collectors.toMap(StationSettlement::getStationId, settlement -> settlement));
        Map<Long, Tariff> tariffsByStation = tariffRepository.findAllWithRelations().stream()
            .filter(tariff -> tariff.getStation() != null && tariff.getStation().getId() != null)
            .collect(java.util.stream.Collectors.toMap(tariff -> tariff.getStation().getId(), tariff -> tariff, (left, right) -> right));

        Map<Long, OwnerFinancialAccumulator> ownerAgg = new java.util.HashMap<>();
        Map<Long, StationFinancialAccumulator> stationAgg = new java.util.HashMap<>();

        double totalCollected = 0.0;
        double totalGst = 0.0;
        double totalPlatformRevenue = 0.0;
        double totalOwnerPayable = 0.0;

        for (ChargingSession session : sessions) {
            Charger charger = session.getCharger();
            Station station = charger == null ? null : charger.getStation();

            Long ownerId = session.getOwnerId() != null
                ? session.getOwnerId()
                : (charger == null ? null : charger.getOwnerId());
            Long stationId = session.getStationId() != null
                ? session.getStationId()
                : (station == null ? null : station.getId());

            String ownerName = ownerId == null
                ? "Unassigned"
                : ownersById.getOrDefault(ownerId, new OwnerAccount()).getName();
            if (ownerName == null || ownerName.isBlank()) {
            ownerName = ownerId == null ? "Unassigned" : "Owner " + ownerId;
            }
            String stationName = station == null || station.getName() == null || station.getName().isBlank()
                ? (stationId == null ? "Unknown Station" : "Station " + stationId)
                : station.getName();

            double collected = nonNull(session.getTotalAmount());
            double gst = nonNull(session.getGstAmount());
            double baseAmount = nonNull(session.getBaseAmount());
            Tariff tariff = stationId == null ? null : tariffsByStation.get(stationId);
            double platformFeePercent = tariff != null && tariff.getPlatformFeePercent() != null
                ? tariff.getPlatformFeePercent()
                : 12.0;
            double platform = roundCurrency(baseAmount * (platformFeePercent / 100.0));
            double ownerPayable = roundCurrency(Math.max(0.0, baseAmount - platform));

            totalCollected += collected;
            totalGst += gst;
            totalPlatformRevenue += platform;
            totalOwnerPayable += ownerPayable;

            Long ownerKey = ownerId == null ? -1L : ownerId;
            OwnerFinancialAccumulator ownerRow = ownerAgg.get(ownerKey);
            if (ownerRow == null) {
                ownerRow = new OwnerFinancialAccumulator(ownerId, ownerName);
                ownerAgg.put(ownerKey, ownerRow);
            }
            ownerRow.sessionCount += 1;
            ownerRow.totalCollected += collected;
            ownerRow.totalGst += gst;
            ownerRow.totalPlatformRevenue += platform;
            ownerRow.totalOwnerPayable += ownerPayable;

            Long stationKey = stationId == null ? -1L : stationId;
            StationFinancialAccumulator stationRow = stationAgg.get(stationKey);
            if (stationRow == null) {
                stationRow = new StationFinancialAccumulator(stationId, stationName, ownerId, ownerName);
                stationAgg.put(stationKey, stationRow);
            }
            stationRow.sessionCount += 1;
            stationRow.totalCollected += collected;
            stationRow.totalGst += gst;
            stationRow.totalPlatformRevenue += platform;
            stationRow.totalOwnerPayable += ownerPayable;
        }

        for (StationFinancialAccumulator stationRow : stationAgg.values()) {
            Long stationId = stationRow.stationId;
            StationSettlement settlement = stationId == null ? null : settlementsByStation.get(stationId);
            stationRow.totalSettled = settlement == null ? 0.0 : nonNull(settlement.getSettledAmount());
            stationRow.totalPending = settlement == null
                ? stationRow.totalOwnerPayable
                : nonNull(settlement.getPendingAmount());
            stationRow.settlementStatus = settlement == null || settlement.getStatus() == null
                ? "PENDING"
                : settlement.getStatus().name();

            Long ownerKey = stationRow.ownerId == null ? -1L : stationRow.ownerId;
            OwnerFinancialAccumulator ownerRow = ownerAgg.computeIfAbsent(ownerKey,
                ignored -> new OwnerFinancialAccumulator(stationRow.ownerId, stationRow.ownerName));
            ownerRow.totalSettled += stationRow.totalSettled;
            ownerRow.totalPending += stationRow.totalPending;
        }

        for (OwnerFinancialAccumulator ownerRow : ownerAgg.values()) {
            if (ownerRow.totalSettled == 0.0 && ownerRow.totalPending == 0.0) {
                ownerRow.totalPending = ownerRow.totalOwnerPayable;
            }
        }

        double totalSettled = ownerAgg.values().stream().mapToDouble(row -> row.totalSettled).sum();
        double totalPending = ownerAgg.values().stream().mapToDouble(row -> row.totalPending).sum();

        List<OwnerFinancialGroup> byOwner = ownerAgg.values().stream()
            .sorted((a, b) -> Double.compare(b.totalCollected, a.totalCollected))
            .map(row -> new OwnerFinancialGroup(
                row.ownerId,
                row.ownerName,
                roundCurrency(row.totalCollected),
                roundCurrency(row.totalGst),
                roundCurrency(row.totalPlatformRevenue),
                roundCurrency(row.totalOwnerPayable),
                roundCurrency(row.totalSettled),
                roundCurrency(row.totalPending),
                row.sessionCount
            ))
            .toList();

        List<StationFinancialGroup> byStation = stationAgg.values().stream()
            .sorted((a, b) -> Double.compare(b.totalCollected, a.totalCollected))
            .map(row -> new StationFinancialGroup(
                row.stationId,
                row.stationName,
                row.ownerId,
                row.ownerName,
                roundCurrency(row.totalCollected),
                roundCurrency(row.totalGst),
                roundCurrency(row.totalPlatformRevenue),
                roundCurrency(row.totalOwnerPayable),
                roundCurrency(row.totalSettled),
                roundCurrency(row.totalPending),
                row.settlementStatus,
                row.sessionCount
            ))
            .toList();

        List<RevenueDistributionPoint> ownerDistribution = byOwner.stream()
            .map(row -> new RevenueDistributionPoint(row.ownerName(), row.totalCollected()))
            .toList();
        List<RevenueDistributionPoint> stationDistribution = byStation.stream()
            .map(row -> new RevenueDistributionPoint(row.stationName(), row.totalCollected()))
            .toList();

        audit(admin.username(), "READ", "FINANCIAL_DASHBOARD", null, "{}");
        return new FinancialDashboardResponse(
            roundCurrency(totalCollected),
            roundCurrency(totalGst),
            roundCurrency(totalPlatformRevenue),
            roundCurrency(totalOwnerPayable),
            roundCurrency(totalSettled),
            roundCurrency(totalPending),
            byOwner,
            byStation,
            new RevenueDistribution(ownerDistribution, stationDistribution)
        );
        }

    @PostMapping("/stations/{stationId}/settlements")
    public StationSettlementResponse markStationSettlement(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long stationId,
            @Valid @RequestBody StationSettlementRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "SUPER_ADMIN");
        StationSettlement settlement = settlementService.markStationSettlement(stationId, request.amount());
        audit(admin.username(), "UPDATE", "STATION_SETTLEMENT", String.valueOf(stationId), "{}");
        return new StationSettlementResponse(
                settlement.getId(),
                settlement.getStationId(),
                settlement.getOwnerId(),
                nonNull(settlement.getTotalRevenue()),
                nonNull(settlement.getSettledAmount()),
                nonNull(settlement.getPendingAmount()),
                settlement.getStatus() == null ? "PENDING" : settlement.getStatus().name()
        );
    }

    @GetMapping("/stations")
    public List<Station> listStations(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        return stationRepository.findAll();
    }

    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    public Station createStation(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody StationUpsertRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        if (request.stationCode() != null && stationRepository.existsByStationCode(request.stationCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Station code already exists");
        }

        Station station = new Station();
        applyStation(station, request);
        station = stationRepository.save(station);

        audit(admin.username(), "CREATE", "STATION", String.valueOf(station.getId()), "{\"name\":\"" + station.getName() + "\"}");
        return station;
    }

    @PutMapping("/stations/{stationId}")
    public Station updateStation(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long stationId,
            @Valid @RequestBody StationUpsertRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));

        applyStation(station, request);
        station = stationRepository.save(station);

        audit(admin.username(), "UPDATE", "STATION", String.valueOf(station.getId()), "{\"name\":\"" + station.getName() + "\"}");
        return station;
    }

    @PatchMapping("/stations/{stationId}/deactivate")
    public Station deactivateStation(@RequestHeader("Authorization") String authorization, @PathVariable Long stationId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));
        station.setStatus("INACTIVE");
        station = stationRepository.save(station);
        audit(admin.username(), "DEACTIVATE", "STATION", String.valueOf(station.getId()), "{}");
        return station;
    }

        @PutMapping("/stations/{stationId}/owner")
        public AssignedStationWithOwnerResponse assignOwnerToStation(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long stationId,
            @Valid @RequestBody AssignOwnerToStationRequest request
        ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Station station = stationRepository.findById(stationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found: " + stationId));

        OwnerAccount owner = ownerAccountRepository.findById(request.ownerId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner not found: " + request.ownerId()));

        assignOwnerToStationAndChargers(owner.getId(), station.getId());

        audit(admin.username(), "ASSIGN", "STATION_OWNER", String.valueOf(stationId), "{}");

        Station updatedStation = stationRepository.findById(stationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found: " + stationId));
        return toAssignedStationWithOwnerResponse(updatedStation, owner);
        }

    @GetMapping("/chargers")
    public List<Charger> listChargers(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) Long stationId
    ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        List<Charger> chargers = stationId != null
                ? chargerRepository.findByStation_Id(stationId)
                : chargerRepository.findAll();
        return syncLiveCommunicationStatus(chargers);
    }

    @PostMapping("/chargers")
    @ResponseStatus(HttpStatus.CREATED)
    public Charger createCharger(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ChargerUpsertRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Station station = stationRepository.findById(request.stationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));

        Charger charger = new Charger();
        charger.setStation(station);
        charger.setOwnerId(station.getOwnerId());
        applyChargerEditableFields(charger, request);
        if (charger.getStatus() == null) {
            charger.setStatus("AVAILABLE");
        }
        if (charger.getEnabled() == null) {
            charger.setEnabled(true);
        }
        if (charger.getCommunicationStatus() == null) {
            charger.setCommunicationStatus("OFFLINE");
        }
        charger = chargerRepository.save(charger);

        audit(admin.username(), "CREATE", "CHARGER", String.valueOf(charger.getId()), "{\"ocppIdentity\":\"" + charger.getOcppIdentity() + "\"}");
        return charger;
    }

    @PutMapping("/chargers/{chargerId}")
    public Charger updateCharger(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId,
            @Valid @RequestBody ChargerUpsertRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

        Station station = stationRepository.findById(request.stationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));

        charger.setStation(station);
        charger.setOwnerId(station.getOwnerId());
        applyChargerEditableFields(charger, request);
        charger = chargerRepository.save(charger);

        audit(admin.username(), "UPDATE", "CHARGER", String.valueOf(charger.getId()), "{}");
        return charger;
    }

    @PatchMapping("/chargers/{chargerId}/enable")
    @Transactional
    public Charger enableCharger(@RequestHeader("Authorization") String authorization, @PathVariable Long chargerId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));
        boolean online = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity());
        String commandStatus = "SKIPPED_OFFLINE";

        if (online) {
            OcppWebSocketHandler.OcppCommandResult result = executeChangeAvailability(charger, 0, "Operative");
            if (!result.isAccepted()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ChangeAvailability rejected: " + result.status());
            }
            commandStatus = result.status();
        }

        charger.setEnabled(true);
        charger.setStatus("AVAILABLE");
        charger = chargerRepository.save(charger);
        restoreConnectorsToAvailable(charger);

        audit(admin.username(), "ENABLE", "CHARGER", String.valueOf(charger.getId()),
                "{\"action\":\"ChangeAvailability\",\"status\":\"" + commandStatus + "\",\"online\":" + online + "}");
        return charger;
    }

    @PatchMapping("/chargers/{chargerId}/disable")
    @Transactional
    public Charger disableCharger(@RequestHeader("Authorization") String authorization, @PathVariable Long chargerId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));
        boolean online = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity());
        String commandStatus = "SKIPPED_OFFLINE";

        if (online) {
            OcppWebSocketHandler.OcppCommandResult result = executeChangeAvailability(charger, 0, "Inoperative");
            if (!result.isAccepted()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "ChangeAvailability rejected: " + result.status());
            }
            commandStatus = result.status();
        }

        charger.setEnabled(false);
        charger.setStatus("UNAVAILABLE");
        charger = chargerRepository.save(charger);
        setAllConnectorsStatus(charger, "UNAVAILABLE");

        audit(admin.username(), "DISABLE", "CHARGER", String.valueOf(charger.getId()),
                "{\"action\":\"ChangeAvailability\",\"status\":\"" + commandStatus + "\",\"online\":" + online + "}");
        return charger;
    }

    @GetMapping("/connectors")
    public List<Connector> listConnectors(
            @RequestHeader("Authorization") String authorization,
            @RequestParam Long chargerId
    ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        return connectorRepository.findByCharger_Id(chargerId);
    }

        @PostMapping("/chargers/{chargerId}/connectors/discover")
        @Transactional
        public ConnectorDiscoveryResponse discoverConnectors(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId
        ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Charger charger = chargerRepository.findById(chargerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

        OcppWebSocketHandler.OcppCommandResult result = executeGetConfiguration(charger, List.of("NumberOfConnectors"));
        Integer discoveredCount = extractNumberOfConnectors(result.payload());

        if (discoveredCount == null || discoveredCount <= 0) {
            return new ConnectorDiscoveryResponse(
                chargerId,
                charger.getOcppIdentity(),
                false,
                null,
                List.of(),
                "NumberOfConnectors not available from charger. Please add connectors manually.",
                result.payload().toString()
            );
        }

        List<Connector> existingConnectors = connectorRepository.findByCharger_Id(chargerId);
        java.util.Set<Integer> existingNumbers = existingConnectors.stream()
            .map(Connector::getConnectorNo)
            .filter(no -> no != null && no > 0)
            .collect(java.util.stream.Collectors.toSet());

        String connectorType = existingConnectors.stream()
            .map(Connector::getType)
            .filter(type -> type != null && !type.isBlank())
            .findFirst()
            .orElse("Type2");

        double maxPowerKw = charger.getMaxPowerKw() == null ? 22.0 : charger.getMaxPowerKw();
        List<Integer> createdConnectorNos = new ArrayList<>();

        for (int connectorNo = 1; connectorNo <= discoveredCount; connectorNo++) {
            if (existingNumbers.contains(connectorNo)) {
            continue;
            }

            Connector connector = new Connector();
            connector.setCharger(charger);
            connector.setConnectorNo(connectorNo);
            connector.setType(connectorType);
            connector.setMaxPowerKw(maxPowerKw);
            connector.setStatus("UNAVAILABLE");
            connectorRepository.save(connector);
            createdConnectorNos.add(connectorNo);
        }

        ocppWebSocketHandler.clearUnknownConnectorAlerts(charger.getOcppIdentity());
        audit(admin.username(), "DISCOVER", "CONNECTOR", String.valueOf(chargerId),
            "{\"discovered\":" + discoveredCount + ",\"created\":" + createdConnectorNos.size() + "}");

        return new ConnectorDiscoveryResponse(
            chargerId,
            charger.getOcppIdentity(),
            true,
            discoveredCount,
            createdConnectorNos,
            createdConnectorNos.isEmpty()
                ? "Charger reports " + discoveredCount + " connector(s). Existing configuration is already in sync."
                : "Discovered " + discoveredCount + " connector(s) and created " + createdConnectorNos.size() + " missing connector(s).",
            result.payload().toString()
        );
        }

        @GetMapping("/chargers/{chargerId}/connectors/unknown-alerts")
        public UnknownConnectorAlertsResponse getUnknownConnectorAlerts(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId
        ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        Charger charger = chargerRepository.findById(chargerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

        List<OcppWebSocketHandler.UnknownConnectorAlert> alerts =
            ocppWebSocketHandler.getUnknownConnectorAlerts(charger.getOcppIdentity());
        List<Integer> unknownConnectorIds = alerts.stream()
            .map(OcppWebSocketHandler.UnknownConnectorAlert::connectorId)
            .filter(id -> id != null && id > 0)
            .collect(java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new),
                ArrayList::new
            ));

        return new UnknownConnectorAlertsResponse(alerts, unknownConnectorIds);
        }

        @DeleteMapping("/chargers/{chargerId}/connectors/unknown-alerts")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void clearUnknownConnectorAlerts(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId
        ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        Charger charger = chargerRepository.findById(chargerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));
        ocppWebSocketHandler.clearUnknownConnectorAlerts(charger.getOcppIdentity());
        }

    @PostMapping("/connectors")
    @ResponseStatus(HttpStatus.CREATED)
    public Connector addConnector(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ConnectorCreateRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Charger charger = chargerRepository.findById(request.chargerId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

        if (connectorRepository.findByCharger_IdAndConnectorNo(request.chargerId(), request.connectorNo()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Connector number already exists for charger");
        }

        Connector connector = new Connector();
        connector.setCharger(charger);
        connector.setConnectorNo(request.connectorNo());
        connector.setType(request.connectorType());
        connector.setMaxPowerKw(request.maxPowerKw());
        connector.setStatus("UNAVAILABLE");

        connector = connectorRepository.save(connector);
        audit(admin.username(), "CREATE", "CONNECTOR", String.valueOf(connector.getId()), "{}");
        return connector;
    }

    @PutMapping("/connectors/{connectorId}")
    public Connector updateConnector(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long connectorId,
            @Valid @RequestBody ConnectorUpdateRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Connector connector = connectorRepository.findById(connectorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found"));

        connector.setType(request.connectorType());
        connector.setMaxPowerKw(request.maxPowerKw());

        connector = connectorRepository.save(connector);
        audit(admin.username(), "UPDATE", "CONNECTOR", String.valueOf(connector.getId()), "{}");
        return connector;
    }

        @DeleteMapping("/connectors/{connectorId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @Transactional
        public void deleteConnector(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long connectorId
        ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Connector connector = connectorRepository.findById(connectorId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found"));

        boolean hasActiveSession = chargingSessionRepository.existsByCharger_IdAndConnectorNoAndStatusIn(
            connector.getCharger().getId(),
            connector.getConnectorNo(),
            ACTIVE_SESSION_STATUSES
        );
        if (hasActiveSession) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot delete connector: an active charging session is in progress");
        }

        if (chargingSessionRepository.existsByConnector_Id(connectorId)) {
            // Keep historical session integrity while removing the connector from active operations/UI.
            connector.setStatus(CONNECTOR_STATUS_DELETED);
            connector.setConnectorNo(-Math.toIntExact(connector.getId()));
            connectorRepository.save(connector);
            audit(admin.username(), "DELETE", "CONNECTOR", String.valueOf(connectorId), "{\"mode\":\"SOFT\"}");
            return;
        }

        try {
            connectorRepository.delete(connector);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot delete connector: it is referenced by existing data", ex);
        }
        audit(admin.username(), "DELETE", "CONNECTOR", String.valueOf(connectorId), "{}");
        }

    @PatchMapping("/connectors/{connectorId}/availability")
    @Transactional
    public Connector setConnectorAvailability(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long connectorId,
            @RequestParam String status,
            @RequestParam(defaultValue = "false") boolean forceLocal
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        if (!status.equals("AVAILABLE") && !status.equals("UNAVAILABLE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be AVAILABLE or UNAVAILABLE");
        }

        Connector connector = connectorRepository.findById(connectorId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found"));
        boolean chargerOnline = ocppWebSocketHandler.isChargerConnected(connector.getCharger().getOcppIdentity());

        if (!chargerOnline && !forceLocal) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot change connector availability");
        }

        // Prevent marking as available if an active session is running on it
        if (status.equals("UNAVAILABLE")) {
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

        if (!chargerOnline && forceLocal) {
            connector.setStatus(status);
            connector = connectorRepository.save(connector);
            audit(admin.username(), "UPDATE", "CONNECTOR_AVAILABILITY", String.valueOf(connector.getId()),
                    "{\"status\":\"" + status + "\",\"action\":\"LOCAL_OVERRIDE\",\"online\":false,\"forceLocal\":true}");
            return connector;
        }

                String ocppType = status.equals("AVAILABLE") ? "Operative" : "Inoperative";
                OcppWebSocketHandler.OcppCommandResult result = executeChangeAvailability(
                    connector.getCharger(),
                    connector.getConnectorNo(),
                    ocppType
                );
                if (!result.isAccepted()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "ChangeAvailability rejected: " + result.status());
                }

        connector.setStatus(status);
        connector = connectorRepository.save(connector);
        audit(admin.username(), "UPDATE", "CONNECTOR_AVAILABILITY", String.valueOf(connector.getId()),
                    "{\"status\":\"" + status + "\",\"action\":\"ChangeAvailability\",\"result\":\"" + result.status() + "\",\"online\":true,\"forceLocal\":false}");
                return connector;
                }

                @PostMapping("/chargers/{chargerId}/reset")
                public OcppCommandResponse resetCharger(
                    @RequestHeader("Authorization") String authorization,
                    @PathVariable Long chargerId,
                    @Valid @RequestBody ChargerResetRequest request
                ) {
                AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

                Charger charger = chargerRepository.findById(chargerId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));
                validateResetType(request.type());

                OcppWebSocketHandler.OcppCommandResult result = executeReset(charger, request.type());
                audit(admin.username(), "RESET", "CHARGER", String.valueOf(chargerId),
                    "{\"type\":\"" + request.type() + "\",\"status\":\"" + result.status() + "\"}");
                return new OcppCommandResponse("Reset", result.status(), result.payload().toString());
                }

                @PostMapping("/connectors/{connectorId}/unlock")
                public OcppCommandResponse unlockConnector(
                    @RequestHeader("Authorization") String authorization,
                    @PathVariable Long connectorId
                ) {
                AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

                Connector connector = connectorRepository.findById(connectorId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Connector not found"));

                OcppWebSocketHandler.OcppCommandResult result = executeUnlock(connector);
                audit(admin.username(), "UNLOCK", "CONNECTOR", String.valueOf(connectorId),
                    "{\"status\":\"" + result.status() + "\"}");
                return new OcppCommandResponse("UnlockConnector", result.status(), result.payload().toString());
    }

            @GetMapping("/chargers/{chargerId}/ocpp/configuration")
            public OcppCommandResponse getChargerConfiguration(
                @RequestHeader("Authorization") String authorization,
                @PathVariable Long chargerId,
                @RequestParam(required = false) List<String> keys
            ) {
            AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
            Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

            OcppWebSocketHandler.OcppCommandResult result = executeGetConfiguration(charger, keys);
            audit(admin.username(), "READ", "CHARGER_CONFIGURATION", String.valueOf(chargerId),
                "{\"keys\":\"" + serializeKeys(keys) + "\",\"status\":\"" + result.status() + "\"}");
            return new OcppCommandResponse("GetConfiguration", result.status(), result.payload().toString());
            }

            @PostMapping("/chargers/{chargerId}/ocpp/configuration/change")
            public OcppCommandResponse changeChargerConfiguration(
                @RequestHeader("Authorization") String authorization,
                @PathVariable Long chargerId,
                @Valid @RequestBody OcppConfigurationChangeRequest request
            ) {
            AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
            Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

            OcppWebSocketHandler.OcppCommandResult result = executeChangeConfiguration(charger, request.key(), request.value());
            audit(admin.username(), "UPDATE", "CHARGER_CONFIGURATION", String.valueOf(chargerId),
                "{\"key\":\"" + request.key() + "\",\"status\":\"" + result.status() + "\"}");
            return new OcppCommandResponse("ChangeConfiguration", result.status(), result.payload().toString());
            }

            @PostMapping("/chargers/{chargerId}/ocpp/trigger-message")
            public OcppCommandResponse triggerChargerMessage(
                @RequestHeader("Authorization") String authorization,
                @PathVariable Long chargerId,
                @Valid @RequestBody OcppTriggerMessageRequest request
            ) {
            AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
            Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));
            validateTriggerMessage(request.requestedMessage());

            OcppWebSocketHandler.OcppCommandResult result = executeTriggerMessage(charger, request.requestedMessage(), request.connectorId());
            audit(admin.username(), "TRIGGER", "CHARGER_MESSAGE", String.valueOf(chargerId),
                "{\"requestedMessage\":\"" + request.requestedMessage() + "\",\"status\":\"" + result.status() + "\"}");
            return new OcppCommandResponse("TriggerMessage", result.status(), result.payload().toString());
            }

            @PostMapping("/chargers/{chargerId}/ocpp/clear-cache")
            public OcppCommandResponse clearChargerCache(
                @RequestHeader("Authorization") String authorization,
                @PathVariable Long chargerId
            ) {
            AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
            Charger charger = chargerRepository.findById(chargerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));

            OcppWebSocketHandler.OcppCommandResult result = executeClearCache(charger);
            audit(admin.username(), "CLEAR", "CHARGER_CACHE", String.valueOf(chargerId),
                "{\"status\":\"" + result.status() + "\"}");
            return new OcppCommandResponse("ClearCache", result.status(), result.payload().toString());
            }

    @GetMapping("/tariffs")
    public List<TariffResponse> listTariffs(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        return tariffRepository.findAllWithRelations().stream().map(this::toTariffResponse).toList();
    }

    @PostMapping("/tariffs")
    @ResponseStatus(HttpStatus.CREATED)
        public TariffResponse createTariff(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody TariffRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Station station = stationRepository.findById(request.stationId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));

        Tariff tariff = new Tariff();
        tariff.setStation(station);
        tariff.setScopeType(request.scopeType());
        tariff.setPricePerKwh(request.pricePerKwh());
        tariff.setGstPercent(request.gstPercent());
        tariff.setIdleFee(request.idleFee());
        tariff.setTimeFee(request.timeFee());
        tariff.setPlatformFeePercent(request.platformFeePercent());
        tariff.setCurrency(request.currency());
        tariff.setEffectiveFrom(request.effectiveFrom());
        tariff.setEffectiveTo(request.effectiveTo());

        if (request.chargerId() != null) {
            Charger charger = chargerRepository.findById(request.chargerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Charger not found"));
            tariff.setCharger(charger);
        }

        tariff = tariffRepository.save(tariff);
        audit(admin.username(), "CREATE", "TARIFF", String.valueOf(tariff.getId()), "{}");
        return toTariffResponse(tariff);
    }

    @PutMapping("/stations/{stationId}/tariff")
    public TariffResponse assignStationTariff(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long stationId,
            @Valid @RequestBody StationTariffRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Station not found"));

        Tariff tariff = tariffRepository.findByStation_Id(stationId).orElseGet(() -> {
            Tariff freshTariff = new Tariff();
            freshTariff.setStation(station);
            freshTariff.setScopeType("STATION");
            freshTariff.setSessionFee(0.0);
            return freshTariff;
        });

        tariff.setStation(station);
        tariff.setScopeType("STATION");
        tariff.setPricePerKwh(request.pricePerKwh());
        tariff.setGstPercent(request.gstPercent());
        tariff.setIdleFee(request.idleFee());
        tariff.setTimeFee(request.timeFee());
        tariff.setPlatformFeePercent(request.platformFeePercent());
        tariff.setCurrency(request.currency());

        tariff = tariffRepository.save(tariff);
        audit(admin.username(), "UPDATE", "STATION_TARIFF", String.valueOf(stationId), "{}");
        return toTariffResponse(tariff);
    }

    @GetMapping("/ocpp-configs")
    public List<OcppConfiguration> listOcppConfigs(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        return ocppConfigurationRepository.findAllByOrderByIdDesc();
    }

    @PostMapping("/ocpp-configs")
    @ResponseStatus(HttpStatus.CREATED)
    public OcppConfiguration createOcppConfig(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody OcppConfigRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        validateOcppConfigRequest(request, null);

        OcppConfiguration config = new OcppConfiguration();
        config.setChargePointIdentity(request.chargePointIdentity());
        config.setWebsocketUrl(request.websocketUrl());
        config.setHeartbeatIntervalSeconds(request.heartbeatIntervalSeconds());
        config.setMeterValueIntervalSeconds(request.meterValueIntervalSeconds());
        config.setAllowedIps(request.allowedIps());
        config.setSecurityMode(request.securityMode());
        config.setTokenValue(request.tokenValue());
        config.setActive(request.active());

        config = ocppConfigurationRepository.save(config);
        audit(admin.username(), "CREATE", "OCPP_CONFIG", String.valueOf(config.getId()), "{}");
        return config;
    }

        @PutMapping("/ocpp-configs/{configId}")
        public OcppConfiguration updateOcppConfig(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long configId,
            @Valid @RequestBody OcppConfigRequest request
        ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        validateOcppConfigRequest(request, configId);

        OcppConfiguration config = ocppConfigurationRepository.findById(configId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OCPP configuration not found"));

        config.setChargePointIdentity(request.chargePointIdentity());
        config.setWebsocketUrl(request.websocketUrl());
        config.setHeartbeatIntervalSeconds(request.heartbeatIntervalSeconds());
        config.setMeterValueIntervalSeconds(request.meterValueIntervalSeconds());
        config.setAllowedIps(request.allowedIps());
        config.setSecurityMode(request.securityMode());
        config.setTokenValue(request.securityMode().equalsIgnoreCase("TOKEN") ? request.tokenValue() : null);
        config.setActive(request.active());

        config = ocppConfigurationRepository.save(config);
        audit(admin.username(), "UPDATE", "OCPP_CONFIG", String.valueOf(config.getId()), "{}");
        return config;
        }

        @DeleteMapping("/ocpp-configs/{configId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void deleteOcppConfig(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long configId
        ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        OcppConfiguration config = ocppConfigurationRepository.findById(configId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "OCPP configuration not found"));

        ocppConfigurationRepository.delete(config);
        audit(admin.username(), "DELETE", "OCPP_CONFIG", String.valueOf(configId), "{}");
        }

        @GetMapping("/owners")
        public List<OwnerAccount> listOwners(
            @RequestHeader("Authorization") String authorization,
            @RequestParam(required = false) Long stationId
    ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        
        if (stationId != null) {
            List<OwnerStationAssignment> assignments = ownerStationAssignmentRepository.findByStationId(stationId);
            List<Long> ownerIds = assignments.stream().map(OwnerStationAssignment::getOwnerId).toList();
            return ownerAccountRepository.findAllById(ownerIds);
        }
        
        return ownerAccountRepository.findAll();
    }

        @PostMapping("/owners")
    @ResponseStatus(HttpStatus.CREATED)
        public OwnerAccount createOwner(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody OwnerCreateRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "SUPER_ADMIN");

        OwnerAccount owner = new OwnerAccount();
        owner.setName(request.name());
        owner.setMobileNumber(request.mobileNumber());
        owner.setPinOrPasswordHash(hashForStorage(request.pinOrPassword()));
        owner.setPermissionsJson(request.permissionsJson());
        owner.setStatus(request.status());

        owner = ownerAccountRepository.save(owner);

        String assignmentRole = request.assignmentRole() == null || request.assignmentRole().isBlank()
                ? "OWNER"
                : request.assignmentRole();

        if (request.assignedStationIds() != null && !request.assignedStationIds().isEmpty()) {
            for (Long stationId : request.assignedStationIds()) {
                stationRepository.findById(stationId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Station not found: " + stationId));
                ownerStationAssignmentRepository.save(new OwnerStationAssignment(owner.getId(), stationId, assignmentRole));
                assignOwnerToStationAndChargers(owner.getId(), stationId);
            }
        }

        audit(admin.username(), "CREATE", "OWNER", String.valueOf(owner.getId()), "{}");
        return owner;
    }

        @DeleteMapping("/owners/{ownerId}")
    @Transactional
        public void deleteOwner(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long ownerId
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "SUPER_ADMIN");

        OwnerAccount owner = ownerAccountRepository.findById(ownerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner not found"));

        List<Station> ownedStations = stationRepository.findAll().stream()
                .filter(station -> ownerId.equals(station.getOwnerId()))
                .toList();
        for (Station station : ownedStations) {
            assignOwnerToStationAndChargers(null, station.getId());
        }

        ownerStationAssignmentRepository.deleteByOwnerId(ownerId);
        ownerAccountRepository.delete(owner);

        audit(admin.username(), "DELETE", "OWNER", String.valueOf(ownerId), "{}");
    }

        @GetMapping("/owners/{ownerId}/assignments")
        public List<OwnerStationAssignmentResponse> getOwnerAssignments(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long ownerId
    ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        ownerAccountRepository.findById(ownerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner not found"));

        return ownerStationAssignmentRepository.findByOwnerId(ownerId)
                .stream()
                .map(assignment -> {
                    String stationName = assignment.getStation() != null ? assignment.getStation().getName() : "Unknown";
                    return new OwnerStationAssignmentResponse(
                            assignment.getStationId(),
                            stationName,
                            assignment.getRole()
                    );
                })
                .toList();
    }

            @PutMapping("/owners/{ownerId}/assignments")
            public List<OwnerStationAssignmentResponse> updateOwnerAssignments(
            @RequestHeader("Authorization") String authorization,
                @PathVariable Long ownerId,
            @Valid @RequestBody OwnerAssignmentUpdateRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "SUPER_ADMIN");

            ownerAccountRepository.findById(ownerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Owner not found"));

            ownerStationAssignmentRepository.deleteByOwnerId(ownerId);

        String role = request.role() == null || request.role().isBlank() ? "OWNER" : request.role();
        if (request.stationIds() != null && !request.stationIds().isEmpty()) {
            for (Long stationId : request.stationIds()) {
                stationRepository.findById(stationId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Station not found: " + stationId));
                ownerStationAssignmentRepository.save(new OwnerStationAssignment(ownerId, stationId, role));
                assignOwnerToStationAndChargers(ownerId, stationId);
            }
        }

            audit(admin.username(), "UPDATE", "OWNER_ASSIGNMENTS", String.valueOf(ownerId), "{}");
            return getOwnerAssignments(authorization, ownerId);
    }

    @GetMapping("/users")
    public List<Customer> listUsers(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        return customerRepository.findAll();
    }

    @PatchMapping("/users/{userId}/block")
    public Customer blockUser(@RequestHeader("Authorization") String authorization, @PathVariable Long userId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Customer customer = customerRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        customer.setStatus("BLOCKED");
        customer = customerRepository.save(customer);
        audit(admin.username(), "BLOCK", "USER", String.valueOf(userId), "{}");
        return customer;
    }

    @PatchMapping("/users/{userId}/unblock")
    public Customer unblockUser(@RequestHeader("Authorization") String authorization, @PathVariable Long userId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        Customer customer = customerRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        customer.setStatus("ACTIVE");
        customer = customerRepository.save(customer);
        audit(admin.username(), "UNBLOCK", "USER", String.valueOf(userId), "{}");
        return customer;
    }

    @GetMapping("/users/{userId}/sessions")
    public List<ChargingSession> userChargingHistory(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long userId
    ) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        Customer customer = customerRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        return chargingSessionRepository.findTop20ByPhoneNumberOrderByCreatedAtDesc(customer.getPhoneNumber());
    }

    @GetMapping("/rfid-tags")
    public List<RfidRegistry> listRfidTags(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");
        return rfidRegistryRepository.findAll();
    }

    @PostMapping("/rfid-tags")
    @ResponseStatus(HttpStatus.CREATED)
    public RfidRegistry createRfidTag(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody RfidCreateRequest request
    ) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        RfidRegistry rfid = new RfidRegistry();
        rfid.setRfidUid(request.rfidUid());
        rfid.setLinkedUserId(request.linkedUserId());
        rfid.setFleetName(request.fleetName());
        rfid.setStatus(request.status());
        rfid.setIssuedDate(request.issuedDate());

        rfid = rfidRegistryRepository.save(rfid);
        audit(admin.username(), "CREATE", "RFID", String.valueOf(rfid.getId()), "{}");
        return rfid;
    }

    @PatchMapping("/rfid-tags/{rfidId}/block")
    public RfidRegistry blockRfid(@RequestHeader("Authorization") String authorization, @PathVariable Long rfidId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        RfidRegistry rfid = rfidRegistryRepository.findById(rfidId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RFID not found"));
        rfid.setStatus("BLOCKED");
        rfid = rfidRegistryRepository.save(rfid);
        audit(admin.username(), "BLOCK", "RFID", String.valueOf(rfid.getId()), "{}");
        return rfid;
    }

    @PatchMapping("/rfid-tags/{rfidId}/unblock")
    public RfidRegistry unblockRfid(@RequestHeader("Authorization") String authorization, @PathVariable Long rfidId) {
        AdminAuthService.AuthenticatedAdmin admin = requireAdmin(authorization, "ADMIN", "SUPER_ADMIN");

        RfidRegistry rfid = rfidRegistryRepository.findById(rfidId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "RFID not found"));
        rfid.setStatus("ACTIVE");
        rfid = rfidRegistryRepository.save(rfid);
        audit(admin.username(), "UNBLOCK", "RFID", String.valueOf(rfid.getId()), "{}");
        return rfid;
    }

    @GetMapping("/logs/system")
    public List<AdminAuditLog> systemLogs(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "SUPER_ADMIN", "ADMIN");
        return adminAuditLogRepository.findAll();
    }

    @GetMapping("/logs/ocpp")
    public List<Map<String, Object>> ocppLogs(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "SUPER_ADMIN", "ADMIN");
        return List.of(
                Map.of("action", "BootNotification", "note", "OCPP logs are persisted to backend.ocpp_message_logs"),
                Map.of("action", "StatusNotification", "note", "Connector availability comes only from OCPP updates"),
                Map.of("action", "StartTransaction", "note", "Live charging session mapping enabled")
        );
    }

    @GetMapping("/logs/completed-sessions")
    public List<CompletedChargingLog> completedSessionLogs(@RequestHeader("Authorization") String authorization) {
        requireAdmin(authorization, "SUPER_ADMIN", "ADMIN");
        return completedChargingLogRepository.findTop200ByOrderByPaymentCompletedAtDesc();
    }

    private AdminAuthService.AuthenticatedAdmin requireAdmin(String authorizationHeader, String... roles) {
        try {
            AdminAuthService.AuthenticatedAdmin admin = adminAuthService.requireAdminFromAuthorizationHeader(authorizationHeader);
            adminAuthService.requireRole(admin, roles);
            return admin;
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    private void applyStation(Station station, StationUpsertRequest request) {
        station.setName(request.stationName());
        station.setStationCode(request.stationCode());
        station.setAddress(request.fullAddress());
        station.setCity(request.city());
        station.setState(request.state());
        station.setPincode(request.pincode());
        station.setLatitude(request.latitude());
        station.setLongitude(request.longitude());
        station.setOperatingHoursType(request.operatingHoursType());
        station.setOperatingHoursJson(request.operatingHoursJson());
        station.setAmenitiesJson(request.amenitiesJson());
        station.setSupportContactNumber(request.supportContactNumber());
        station.setPaymentMethodsJson(request.paymentMethodsJson());
        station.setMapEmbedHtml(request.mapEmbedHtml());
        station.setStatus(request.status());
    }

    private void applyChargerEditableFields(Charger charger, ChargerUpsertRequest request) {
        charger.setName(request.chargerName());
        charger.setOcppIdentity(request.chargePointIdentity());
        charger.setVendorName(request.vendorName());
        charger.setModel(request.model());
        charger.setSerialNumber(request.serialNumber());
        charger.setChargerType(request.chargerType());
        charger.setMaxPowerKw(request.maxPowerKw());
        charger.setOcppVersion(request.ocppVersion());
        charger.setStatus(request.status());
    }

    private List<Charger> syncLiveCommunicationStatus(List<Charger> chargers) {
        if (chargers.isEmpty()) {
            return chargers;
        }

        List<Charger> changed = new java.util.ArrayList<>();
        for (Charger charger : chargers) {
            String liveStatus = ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity())
                    ? "ONLINE"
                    : "OFFLINE";
            if (!liveStatus.equalsIgnoreCase(charger.getCommunicationStatus())) {
                charger.setCommunicationStatus(liveStatus);
                changed.add(charger);
            }
        }

        if (!changed.isEmpty()) {
            chargerRepository.saveAll(changed);
        }
        return chargers;
    }

    private OcppWebSocketHandler.OcppCommandResult executeChangeAvailability(Charger charger, int connectorId, String availabilityType) {
        try {
            return ocppWebSocketHandler.changeAvailability(charger.getOcppIdentity(), connectorId, availabilityType);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeReset(Charger charger, String resetType) {
        if (!ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot reset");
        }
        try {
            return ocppWebSocketHandler.reset(charger.getOcppIdentity(), resetType);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeUnlock(Connector connector) {
        if (!ocppWebSocketHandler.isChargerConnected(connector.getCharger().getOcppIdentity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot unlock connector");
        }
        try {
            return ocppWebSocketHandler.unlockConnector(connector.getCharger().getOcppIdentity(), connector.getConnectorNo());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeGetConfiguration(Charger charger, List<String> keys) {
        ensureChargerConnected(charger, "read configuration");
        try {
            return ocppWebSocketHandler.getConfiguration(charger.getOcppIdentity(), keys);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeChangeConfiguration(Charger charger, String key, String value) {
        ensureChargerConnected(charger, "change configuration");
        try {
            return ocppWebSocketHandler.changeConfiguration(charger.getOcppIdentity(), key, value);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeTriggerMessage(Charger charger, String requestedMessage, Integer connectorId) {
        ensureChargerConnected(charger, "trigger message");
        try {
            return ocppWebSocketHandler.triggerMessage(charger.getOcppIdentity(), requestedMessage, connectorId);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private OcppWebSocketHandler.OcppCommandResult executeClearCache(Charger charger) {
        ensureChargerConnected(charger, "clear cache");
        try {
            return ocppWebSocketHandler.clearCache(charger.getOcppIdentity());
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ex.getMessage(), ex);
        }
    }

    private void ensureChargerConnected(Charger charger, String action) {
        if (!ocppWebSocketHandler.isChargerConnected(charger.getOcppIdentity())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Charger is offline; cannot " + action);
        }
    }

    private void validateResetType(String type) {
        if (!"Hard".equalsIgnoreCase(type) && !"Soft".equalsIgnoreCase(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset type must be Hard or Soft");
        }
    }

    private void validateTriggerMessage(String requestedMessage) {
        List<String> allowed = List.of("BootNotification", "DiagnosticsStatusNotification", "FirmwareStatusNotification", "Heartbeat", "MeterValues", "StatusNotification");
        if (allowed.stream().noneMatch(value -> value.equalsIgnoreCase(requestedMessage))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported requestedMessage");
        }
    }

    private String serializeKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return "ALL";
        }
        return String.join(",", keys);
    }

    private Integer extractNumberOfConnectors(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return null;
        }

        JsonNode configurationKey = payload.path("configurationKey");
        if (!configurationKey.isArray()) {
            return null;
        }

        for (JsonNode item : configurationKey) {
            String key = item.path("key").asText("");
            if (!"NumberOfConnectors".equalsIgnoreCase(key)) {
                continue;
            }

            String value = item.path("value").asText("").trim();
            if (value.isBlank()) {
                return null;
            }

            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return null;
    }

    private void setAllConnectorsStatus(Charger charger, String status) {
        List<Connector> connectors = connectorRepository.findByCharger_Id(charger.getId());
        for (Connector connector : connectors) {
            if (CONNECTOR_STATUS_DELETED.equalsIgnoreCase(connector.getStatus())) {
                continue;
            }
            if (!chargingSessionRepository.existsByCharger_IdAndConnectorNoAndStatusIn(
                    charger.getId(),
                    connector.getConnectorNo(),
                    ACTIVE_SESSION_STATUSES
            )) {
                connector.setStatus(status);
            }
        }
        connectorRepository.saveAll(connectors);
    }

    private void restoreConnectorsToAvailable(Charger charger) {
        List<Connector> connectors = connectorRepository.findByCharger_Id(charger.getId());
        for (Connector connector : connectors) {
            if (CONNECTOR_STATUS_DELETED.equalsIgnoreCase(connector.getStatus())) {
                continue;
            }
            if (!chargingSessionRepository.existsByCharger_IdAndConnectorNoAndStatusIn(
                    charger.getId(),
                    connector.getConnectorNo(),
                    ACTIVE_SESSION_STATUSES
            )) {
                connector.setStatus("AVAILABLE");
            }
        }
        connectorRepository.saveAll(connectors);
    }

    private void validateOcppConfigRequest(OcppConfigRequest request, Long existingConfigId) {
        if (request.securityMode() != null
                && request.securityMode().equalsIgnoreCase("TOKEN")
                && (request.tokenValue() == null || request.tokenValue().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Token is required for TOKEN security mode");
        }

        boolean duplicateIdentity = existingConfigId == null
                ? ocppConfigurationRepository.existsByChargePointIdentityIgnoreCase(request.chargePointIdentity())
                : ocppConfigurationRepository.existsByChargePointIdentityIgnoreCaseAndIdNot(
                        request.chargePointIdentity(),
                        existingConfigId
                );

        if (duplicateIdentity) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "OCPP configuration already exists for this Charge Point Identity"
            );
        }
    }

    private String hashForStorage(String value) {
        return Integer.toHexString(value.hashCode());
    }

    private AssignedStationWithOwnerResponse toAssignedStationWithOwnerResponse(Station station, OwnerAccount owner) {
        return new AssignedStationWithOwnerResponse(
                station.getId(),
                station.getName(),
                station.getStationCode(),
                station.getStatus(),
                station.getOwnerId(),
                owner.getName(),
                owner.getMobileNumber()
        );
    }

    private void assignOwnerToStationAndChargers(Long ownerId, Long stationId) {
        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Station not found: " + stationId));
        station.setOwnerId(ownerId);
        stationRepository.save(station);

        List<Charger> chargers = chargerRepository.findByStation_Id(stationId);
        for (Charger charger : chargers) {
            charger.setOwnerId(ownerId);
        }
        chargerRepository.saveAll(chargers);
    }

    private void audit(String actorUsername, String action, String resourceType, String resourceId, String detailsJson) {
        AdminAuditLog log = new AdminAuditLog();
        log.setActorUsername(actorUsername);
        log.setAction(action);
        log.setResourceType(resourceType);
        log.setResourceId(resourceId);
        log.setDetailsJson(detailsJson);
        adminAuditLogRepository.save(log);
    }

    private TariffResponse toTariffResponse(Tariff tariff) {
        Station station = tariff.getStation();
        Charger charger = tariff.getCharger();

        return new TariffResponse(
                tariff.getId(),
                station == null ? null : station.getId(),
                station == null ? null : station.getName(),
                charger == null ? null : charger.getId(),
                tariff.getScopeType(),
                tariff.getPricePerKwh(),
                tariff.getGstPercent(),
                tariff.getIdleFee(),
                tariff.getTimeFee(),
                tariff.getPlatformFeePercent(),
                tariff.getCurrency(),
                tariff.getEffectiveFrom(),
                tariff.getEffectiveTo()
        );
    }

    private double nonNull(Double value) {
        return value == null ? 0.0 : value;
    }

    private double roundCurrency(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static final class OwnerFinancialAccumulator {
        private final Long ownerId;
        private final String ownerName;
        private long sessionCount;
        private double totalCollected;
        private double totalGst;
        private double totalPlatformRevenue;
        private double totalOwnerPayable;
        private double totalSettled;
        private double totalPending;

        private OwnerFinancialAccumulator(Long ownerId, String ownerName) {
            this.ownerId = ownerId;
            this.ownerName = ownerName;
        }
    }

    private static final class StationFinancialAccumulator {
        private final Long stationId;
        private final String stationName;
        private final Long ownerId;
        private final String ownerName;
        private long sessionCount;
        private double totalCollected;
        private double totalGst;
        private double totalPlatformRevenue;
        private double totalOwnerPayable;
        private double totalSettled;
        private double totalPending;
        private String settlementStatus = "PENDING";

        private StationFinancialAccumulator(Long stationId, String stationName, Long ownerId, String ownerName) {
            this.stationId = stationId;
            this.stationName = stationName;
            this.ownerId = ownerId;
            this.ownerName = ownerName;
        }
    }

    public record DashboardSummaryResponse(
            long totalStations,
            long totalChargers,
            long onlineChargers,
            long offlineChargers,
            long activeChargingSessions,
            double revenueToday
    ) {
    }

        public record FinancialDashboardResponse(
            double totalCollected,
            double totalGST,
            double totalPlatformRevenue,
            double totalOwnerPayable,
            double totalSettled,
            double totalPending,
            List<OwnerFinancialGroup> byOwner,
            List<StationFinancialGroup> byStation,
            RevenueDistribution revenueDistribution
        ) {
        }

        public record OwnerFinancialGroup(
            Long ownerId,
            String ownerName,
            double totalCollected,
            double totalGST,
            double totalPlatformRevenue,
            double totalOwnerPayable,
            double totalSettled,
            double totalPending,
            long totalSessions
        ) {
        }

        public record StationFinancialGroup(
            Long stationId,
            String stationName,
            Long ownerId,
            String ownerName,
            double totalCollected,
            double totalGST,
            double totalPlatformRevenue,
            double totalOwnerPayable,
            double totalSettled,
            double totalPending,
            String settlementStatus,
            long totalSessions
        ) {
        }

        public record StationSettlementRequest(
            @NotNull Double amount
        ) {
        }

        public record StationSettlementResponse(
            Long id,
            Long stationId,
            Long ownerId,
            double totalRevenue,
            double settledAmount,
            double pendingAmount,
            String status
        ) {
        }

        public record RevenueDistribution(
            List<RevenueDistributionPoint> byOwner,
            List<RevenueDistributionPoint> byStation
        ) {
        }

        public record RevenueDistributionPoint(
            String label,
            double value
        ) {
        }

    public record StationUpsertRequest(
            @NotBlank String stationName,
            @NotBlank String stationCode,
            @NotBlank String fullAddress,
            @NotBlank String city,
            @NotBlank String state,
            @NotBlank String pincode,
            @NotNull Double latitude,
            @NotNull Double longitude,
            @NotBlank String operatingHoursType,
            @NotBlank String operatingHoursJson,
            @NotBlank String amenitiesJson,
            @NotBlank String supportContactNumber,
            @NotBlank String paymentMethodsJson,
            String mapEmbedHtml,
            @NotBlank String status
    ) {
    }

    public record ChargerUpsertRequest(
            @NotNull Long stationId,
            @NotBlank String chargerName,
            @NotBlank String chargePointIdentity,
            String vendorName,
            String model,
            String serialNumber,
            @NotBlank String chargerType,
            @NotNull Double maxPowerKw,
            @NotBlank String ocppVersion,
            @NotBlank String status
    ) {
    }

    public record ConnectorCreateRequest(
            @NotNull Long chargerId,
            @NotNull Integer connectorNo,
            @NotBlank String connectorType,
            @NotNull Double maxPowerKw
    ) {
    }

    public record ConnectorUpdateRequest(
            @NotBlank String connectorType,
            @NotNull Double maxPowerKw
    ) {
    }

        public record ChargerResetRequest(
            @NotBlank String type
        ) {
        }

            public record OcppConfigurationChangeRequest(
                @NotBlank String key,
                @NotBlank String value
            ) {
            }

            public record OcppTriggerMessageRequest(
                @NotBlank String requestedMessage,
                Integer connectorId
            ) {
            }

        public record OcppCommandResponse(
            String action,
            String status,
            String payload
        ) {
        }

            public record ConnectorDiscoveryResponse(
                Long chargerId,
                String chargePointIdentity,
                boolean discovered,
                Integer discoveredConnectorCount,
                List<Integer> createdConnectorNos,
                String message,
                String payload
            ) {
            }

            public record UnknownConnectorAlertsResponse(
                List<OcppWebSocketHandler.UnknownConnectorAlert> alerts,
                List<Integer> unknownConnectorIds
            ) {
            }

    private static final java.util.Set<String> ACTIVE_SESSION_STATUSES = java.util.Set.of(
            "PENDING_VERIFICATION", "PENDING_PAYMENT", "PENDING_START", "ACTIVE", "STOPPING"
    );

    public record TariffRequest(
            @NotNull Long stationId,
            Long chargerId,
            @NotBlank String scopeType,
            @NotNull Double pricePerKwh,
            @NotNull Double gstPercent,
            @NotNull Double idleFee,
            @NotNull Double timeFee,
            @NotNull Double platformFeePercent,
            @NotBlank String currency,
            LocalDateTime effectiveFrom,
            LocalDateTime effectiveTo
    ) {
    }

            public record TariffResponse(
                Long id,
                Long stationId,
                String stationName,
                Long chargerId,
                String scopeType,
                Double pricePerKwh,
                Double gstPercent,
                Double idleFee,
                Double timeFee,
                Double platformFeePercent,
                String currency,
                LocalDateTime effectiveFrom,
                LocalDateTime effectiveTo
            ) {
            }

            public record StationTariffRequest(
                @NotNull Double pricePerKwh,
                @NotNull Double gstPercent,
                @NotNull Double idleFee,
                @NotNull Double timeFee,
                @NotNull Double platformFeePercent,
                @NotBlank String currency
            ) {
            }

    public record OcppConfigRequest(
            @NotBlank String chargePointIdentity,
            @NotBlank String websocketUrl,
            @NotNull Integer heartbeatIntervalSeconds,
            @NotNull Integer meterValueIntervalSeconds,
            String allowedIps,
            @NotBlank String securityMode,
            String tokenValue,
            @NotNull Boolean active
    ) {
    }

        public record AssignOwnerToStationRequest(
            @NotNull Long ownerId
        ) {
        }

        public record AssignedStationWithOwnerResponse(
            Long stationId,
            String stationName,
            String stationCode,
            String stationStatus,
            Long ownerId,
            String ownerName,
            String ownerMobileNumber
        ) {
        }

    public record OwnerCreateRequest(
            @NotBlank String name,
            @NotBlank String mobileNumber,
            @NotBlank String pinOrPassword,
            @NotBlank String permissionsJson,
            @NotBlank String status,
            List<Long> assignedStationIds,
            String assignmentRole
    ) {
    }

        public record OwnerAssignmentUpdateRequest(
            List<Long> stationIds,
            String role
        ) {
        }

        public record OwnerStationAssignmentResponse(
            Long stationId,
            String stationName,
            String role
        ) {
        }

    public record RfidCreateRequest(
            @NotBlank String rfidUid,
            Long linkedUserId,
            String fleetName,
            @NotBlank String status,
            LocalDate issuedDate
    ) {
    }
}
