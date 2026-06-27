package com.evcsms.backend.repository;

import com.evcsms.backend.model.ChargerStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChargerStatusLogRepository extends JpaRepository<ChargerStatusLog, Long> {

    List<ChargerStatusLog> findByChargerIdAndStartedAtBetweenOrderByStartedAtAsc(
            Long chargerId, LocalDateTime from, LocalDateTime to);

    List<ChargerStatusLog> findByStartedAtBetweenOrderByChargerIdAscStartedAtAsc(
            LocalDateTime from, LocalDateTime to);

    Optional<ChargerStatusLog> findTopByOcppIdentityAndEndedAtIsNullOrderByStartedAtDesc(String ocppIdentity);
}
