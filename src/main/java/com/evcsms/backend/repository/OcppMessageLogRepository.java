package com.evcsms.backend.repository;

import com.evcsms.backend.model.OcppMessageLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OcppMessageLogRepository extends JpaRepository<OcppMessageLog, Long> {
}