package com.evcsms.backend.repository;

import com.evcsms.backend.model.OcppConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OcppConfigurationRepository extends JpaRepository<OcppConfiguration, Long> {
    @Query("SELECT c FROM OcppConfiguration c WHERE c.chargePointIdentity = :id AND c.active = true ORDER BY c.id ASC")
    List<OcppConfiguration> findActiveByChargePointIdentity(@Param("id") String chargePointIdentity);

    List<OcppConfiguration> findAllByOrderByIdDesc();

    boolean existsByChargePointIdentityIgnoreCase(String chargePointIdentity);

    boolean existsByChargePointIdentityIgnoreCaseAndIdNot(String chargePointIdentity, Long id);

    default Optional<OcppConfiguration> findByChargePointIdentity(String chargePointIdentity) {
        List<OcppConfiguration> results = findActiveByChargePointIdentity(chargePointIdentity);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
