package com.evcsms.backend.repository;

import com.evcsms.backend.model.CompletedChargingLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CompletedChargingLogRepository extends JpaRepository<CompletedChargingLog, Long> {
    Optional<CompletedChargingLog> findBySessionId(Long sessionId);

    List<CompletedChargingLog> findTop200ByOrderByPaymentCompletedAtDesc();

    List<CompletedChargingLog> findTop200ByStationIdInOrderByPaymentCompletedAtDesc(Collection<Long> stationIds);
}
