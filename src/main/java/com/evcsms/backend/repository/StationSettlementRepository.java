package com.evcsms.backend.repository;

import com.evcsms.backend.model.StationSettlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StationSettlementRepository extends JpaRepository<StationSettlement, Long> {

    Optional<StationSettlement> findByStationId(Long stationId);

    List<StationSettlement> findByOwnerId(Long ownerId);
}