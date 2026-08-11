package com.evcsms.backend.repository;

import com.evcsms.backend.model.Charger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository("backendChargerRepository")
public interface ChargerRepository extends JpaRepository<Charger, Long> {

    Optional<Charger> findByOcppIdentity(String ocppIdentity);

    // Fallback for resolving identities that differ from the stored value only by case
    // or leading/trailing whitespace (e.g. an operator typo during charger provisioning).
    @Query("SELECT c FROM Charger c WHERE LOWER(TRIM(c.ocppIdentity)) = LOWER(TRIM(:ocppIdentity))")
    Optional<Charger> findByOcppIdentityNormalized(@Param("ocppIdentity") String ocppIdentity);

    List<Charger> findByStation_Id(Long stationId);

    boolean existsByStation_Id(Long stationId);

    Long countByCommunicationStatus(String communicationStatus);
}
