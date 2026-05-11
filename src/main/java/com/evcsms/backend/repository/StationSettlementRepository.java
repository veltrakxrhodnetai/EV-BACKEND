package com.evcsms.backend.repository;

import com.evcsms.backend.model.StationSettlement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface StationSettlementRepository extends JpaRepository<StationSettlement, Long> {

    Optional<StationSettlement> findByStationId(Long stationId);

    boolean existsByStationId(Long stationId);

    List<StationSettlement> findByOwnerId(Long ownerId);

    @Modifying
    @Transactional
    long deleteByStationId(Long stationId);
}