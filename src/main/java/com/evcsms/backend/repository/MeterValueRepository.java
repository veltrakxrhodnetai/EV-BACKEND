package com.evcsms.backend.repository;

import com.evcsms.backend.model.MeterValue;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeterValueRepository extends JpaRepository<MeterValue, Long> {

    List<MeterValue> findBySessionId(Long sessionId);

    Optional<MeterValue> findTopBySessionIdOrderByTimestampDesc(Long sessionId);

    Optional<MeterValue> findTopBySessionIdOrderByTimestampDescIdDesc(Long sessionId);

    default Optional<MeterValue> findLatestBySessionId(Long sessionId) {
        Optional<MeterValue> latest = findTopBySessionIdOrderByTimestampDescIdDesc(sessionId);
        if (latest.isPresent()) {
            return latest;
        }
        return findTopBySessionIdOrderByTimestampDesc(sessionId);
    }
}
