package com.evcsms.backend.repository;

import com.evcsms.backend.model.Charger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository("backendChargerRepository")
public interface ChargerRepository extends JpaRepository<Charger, Long> {

    Optional<Charger> findByOcppIdentity(String ocppIdentity);

    List<Charger> findByStation_Id(Long stationId);

    Long countByCommunicationStatus(String communicationStatus);
}
