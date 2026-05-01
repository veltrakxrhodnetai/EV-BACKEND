package com.evcsms.backend.repository;

import com.evcsms.backend.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement, Long> {

    Optional<Settlement> findByOwnerId(Long ownerId);
}
