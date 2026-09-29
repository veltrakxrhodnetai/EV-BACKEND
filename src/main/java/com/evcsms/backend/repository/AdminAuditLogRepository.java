package com.evcsms.backend.repository;

import com.evcsms.backend.model.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
    List<AdminAuditLog> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(LocalDateTime cutoff);

    long deleteByCreatedAtBefore(LocalDateTime cutoff);
}
