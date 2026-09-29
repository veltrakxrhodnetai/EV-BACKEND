package com.evcsms.backend.service;

import com.evcsms.backend.model.AdminAuditLog;
import com.evcsms.backend.repository.AdminAuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AdminAuditLogRetentionService {

    private static final Logger logger = LoggerFactory.getLogger(AdminAuditLogRetentionService.class);

    private final AdminAuditLogRepository adminAuditLogRepository;
    private final int retentionDays;

    public AdminAuditLogRetentionService(
            AdminAuditLogRepository adminAuditLogRepository,
            @Value("${app.logs.system-retention-days:7}") int retentionDays
    ) {
        if (retentionDays < 1) {
            throw new IllegalArgumentException("System log retention must be at least one day");
        }
        this.adminAuditLogRepository = adminAuditLogRepository;
        this.retentionDays = retentionDays;
    }

    @Transactional(readOnly = true)
    public List<AdminAuditLog> getRetainedLogs() {
        return adminAuditLogRepository.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(retentionCutoff());
    }

    @Scheduled(cron = "${app.logs.system-cleanup-cron:0 15 2 * * *}")
    @Transactional
    public void deleteExpiredLogs() {
        LocalDateTime cutoff = retentionCutoff();
        long deletedCount = adminAuditLogRepository.deleteByCreatedAtBefore(cutoff);
        if (deletedCount > 0) {
            logger.info("Deleted {} admin system logs older than {}", deletedCount, cutoff);
        }
    }

    private LocalDateTime retentionCutoff() {
        return LocalDateTime.now().minusDays(retentionDays);
    }
}
