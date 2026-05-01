package com.evcsms.backend.service;

import com.evcsms.backend.model.Settlement;
import com.evcsms.backend.model.Station;
import com.evcsms.backend.model.StationSettlement;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.OwnerAccountRepository;
import com.evcsms.backend.repository.SettlementRepository;
import com.evcsms.backend.repository.StationRepository;
import com.evcsms.backend.repository.StationSettlementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final ChargingSessionRepository chargingSessionRepository;
    private final OwnerAccountRepository ownerAccountRepository;
    private final StationRepository stationRepository;
    private final StationSettlementRepository stationSettlementRepository;

    public SettlementService(
            SettlementRepository settlementRepository,
            ChargingSessionRepository chargingSessionRepository,
            OwnerAccountRepository ownerAccountRepository,
            StationRepository stationRepository,
            StationSettlementRepository stationSettlementRepository
    ) {
        this.settlementRepository = settlementRepository;
        this.chargingSessionRepository = chargingSessionRepository;
        this.ownerAccountRepository = ownerAccountRepository;
        this.stationRepository = stationRepository;
        this.stationSettlementRepository = stationSettlementRepository;
    }

    @Transactional
    public Settlement markSettlement(Long ownerId, Double amount) {
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (amount == null || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }
        if (!ownerAccountRepository.existsById(ownerId)) {
            throw new IllegalArgumentException("Owner not found: " + ownerId);
        }

        Settlement settlement = settlementRepository.findByOwnerId(ownerId)
                .orElseGet(() -> {
                    Settlement created = new Settlement();
                    created.setOwnerId(ownerId);
                    return created;
                });

        double totalRevenue = roundCurrency(chargingSessionRepository.sumOwnerRevenueByOwnerId(ownerId));
        double nextSettled = roundCurrency((settlement.getSettledAmount() == null ? 0.0 : settlement.getSettledAmount()) + amount);
        if (nextSettled > totalRevenue) {
            nextSettled = totalRevenue;
        }

        settlement.setTotalRevenue(totalRevenue);
        settlement.setSettledAmount(nextSettled);
        applyPendingAndStatus(settlement);

        return settlementRepository.save(settlement);
    }

    @Transactional(readOnly = true)
    public Settlement getSettlementStatus(Long ownerId) {
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId is required");
        }
        if (!ownerAccountRepository.existsById(ownerId)) {
            throw new IllegalArgumentException("Owner not found: " + ownerId);
        }

        Settlement settlement = settlementRepository.findByOwnerId(ownerId)
                .orElseGet(() -> {
                    Settlement empty = new Settlement();
                    empty.setOwnerId(ownerId);
                    empty.setSettledAmount(0.0);
                    return empty;
                });

        double totalRevenue = roundCurrency(chargingSessionRepository.sumOwnerRevenueByOwnerId(ownerId));
        settlement.setTotalRevenue(totalRevenue);

        double settled = settlement.getSettledAmount() == null ? 0.0 : settlement.getSettledAmount();
        if (settled > totalRevenue) {
            settlement.setSettledAmount(totalRevenue);
        }

        applyPendingAndStatus(settlement);
        return settlement;
    }

    @Transactional
    public StationSettlement markStationSettlement(Long stationId, Double amount) {
        if (stationId == null) {
            throw new IllegalArgumentException("stationId is required");
        }
        if (amount == null || amount <= 0.0) {
            throw new IllegalArgumentException("amount must be greater than 0");
        }

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new IllegalArgumentException("Station not found: " + stationId));

        StationSettlement settlement = stationSettlementRepository.findByStationId(stationId)
                .orElseGet(() -> {
                    StationSettlement created = new StationSettlement();
                    created.setStationId(stationId);
                    created.setOwnerId(station.getOwnerId());
                    return created;
                });

        double totalRevenue = roundCurrency(chargingSessionRepository.findCompletedFinancialSessions(java.util.List.of("PAID", "CAPTURED")).stream()
                .filter(session -> {
                    Long sessionStationId = session.getStationId();
                    if (sessionStationId != null) {
                        return stationId.equals(sessionStationId);
                    }
                    return session.getCharger() != null && session.getCharger().getStationId() != null && stationId.equals(session.getCharger().getStationId());
                })
                .mapToDouble(session -> session.getOwnerRevenue() == null ? 0.0 : session.getOwnerRevenue())
                .sum());

        double nextSettled = roundCurrency((settlement.getSettledAmount() == null ? 0.0 : settlement.getSettledAmount()) + amount);
        if (nextSettled > totalRevenue) {
            nextSettled = totalRevenue;
        }

        settlement.setOwnerId(station.getOwnerId());
        settlement.setTotalRevenue(totalRevenue);
        settlement.setSettledAmount(nextSettled);
        applyStationPendingAndStatus(settlement);
        return stationSettlementRepository.save(settlement);
    }

    @Transactional(readOnly = true)
    public StationSettlement getStationSettlementStatus(Long stationId) {
        if (stationId == null) {
            throw new IllegalArgumentException("stationId is required");
        }

        Station station = stationRepository.findById(stationId)
                .orElseThrow(() -> new IllegalArgumentException("Station not found: " + stationId));

        StationSettlement settlement = stationSettlementRepository.findByStationId(stationId)
                .orElseGet(() -> {
                    StationSettlement created = new StationSettlement();
                    created.setStationId(stationId);
                    created.setOwnerId(station.getOwnerId());
                    created.setSettledAmount(0.0);
                    return created;
                });

        double totalRevenue = roundCurrency(chargingSessionRepository.findCompletedFinancialSessions(java.util.List.of("PAID", "CAPTURED")).stream()
                .filter(session -> {
                    Long sessionStationId = session.getStationId();
                    if (sessionStationId != null) {
                        return stationId.equals(sessionStationId);
                    }
                    return session.getCharger() != null && session.getCharger().getStationId() != null && stationId.equals(session.getCharger().getStationId());
                })
                .mapToDouble(session -> session.getOwnerRevenue() == null ? 0.0 : session.getOwnerRevenue())
                .sum());

        settlement.setOwnerId(station.getOwnerId());
        settlement.setTotalRevenue(totalRevenue);
        if ((settlement.getSettledAmount() == null ? 0.0 : settlement.getSettledAmount()) > totalRevenue) {
            settlement.setSettledAmount(totalRevenue);
        }
        applyStationPendingAndStatus(settlement);
        return settlement;
    }

    private void applyPendingAndStatus(Settlement settlement) {
        double totalRevenue = settlement.getTotalRevenue() == null ? 0.0 : settlement.getTotalRevenue();
        double settledAmount = settlement.getSettledAmount() == null ? 0.0 : settlement.getSettledAmount();
        double pendingAmount = roundCurrency(totalRevenue - settledAmount);
        if (pendingAmount < 0) {
            pendingAmount = 0.0;
        }

        settlement.setPendingAmount(pendingAmount);

        if (settledAmount <= 0.0) {
            settlement.setStatus(Settlement.SettlementStatus.PENDING);
            return;
        }

        if (pendingAmount <= 0.0) {
            settlement.setStatus(Settlement.SettlementStatus.COMPLETED);
        } else {
            settlement.setStatus(Settlement.SettlementStatus.PARTIAL);
        }
    }

    private void applyStationPendingAndStatus(StationSettlement settlement) {
        double totalRevenue = settlement.getTotalRevenue() == null ? 0.0 : settlement.getTotalRevenue();
        double settledAmount = settlement.getSettledAmount() == null ? 0.0 : settlement.getSettledAmount();
        double pendingAmount = roundCurrency(totalRevenue - settledAmount);
        if (pendingAmount < 0) {
            pendingAmount = 0.0;
        }

        settlement.setPendingAmount(pendingAmount);

        if (settledAmount <= 0.0) {
            settlement.setStatus(StationSettlement.SettlementStatus.PENDING);
            return;
        }

        if (pendingAmount <= 0.0) {
            settlement.setStatus(StationSettlement.SettlementStatus.COMPLETED);
        } else {
            settlement.setStatus(StationSettlement.SettlementStatus.PARTIAL);
        }
    }

    private double roundCurrency(Double value) {
        double amount = value == null ? 0.0 : value;
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
