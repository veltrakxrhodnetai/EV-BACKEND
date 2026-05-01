package com.evcsms.backend.repository;

import com.evcsms.backend.model.Station;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StationRepository extends JpaRepository<Station, Long> {

    List<Station> findByStatus(String status);

    boolean existsByStationCode(String stationCode);
}
