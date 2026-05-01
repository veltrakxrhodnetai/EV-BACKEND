package com.evcsms.repository;

import com.evcsms.model.Charger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChargerRepository extends JpaRepository<Charger, Long> {

    Optional<Charger> findByOcppIdentity(String ocppIdentity);
}
