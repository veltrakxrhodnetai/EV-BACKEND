package com.evcsms.backend.repository;

import com.evcsms.backend.model.RfidRegistry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RfidRegistryRepository extends JpaRepository<RfidRegistry, Long> {
	boolean existsByLinkedUserId(Long linkedUserId);
}
