package com.evcsms.backend.repository;

import com.evcsms.backend.model.ChargerStatusLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChargerStatusLogRepository extends JpaRepository<ChargerStatusLog, Long> {

    @Query("""
            SELECT log
            FROM ChargerStatusLog log
            WHERE log.chargerId = :chargerId
              AND log.startedAt < :to
              AND (log.endedAt IS NULL OR log.endedAt > :from)
            ORDER BY log.startedAt ASC
            """)
    List<ChargerStatusLog> findOverlappingByChargerId(
            @Param("chargerId") Long chargerId,
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    @Query("""
            SELECT log
            FROM ChargerStatusLog log
            WHERE log.startedAt < :to
              AND (log.endedAt IS NULL OR log.endedAt > :from)
            ORDER BY log.chargerId ASC, log.startedAt ASC
            """)
    List<ChargerStatusLog> findOverlapping(
            @Param("from") LocalDateTime from,
            @Param("to") LocalDateTime to);

    Optional<ChargerStatusLog> findTopByOcppIdentityAndEndedAtIsNullOrderByStartedAtDesc(String ocppIdentity);
}
