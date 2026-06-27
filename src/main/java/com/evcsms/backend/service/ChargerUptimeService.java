package com.evcsms.backend.service;

import com.evcsms.backend.model.Charger;
import com.evcsms.backend.model.ChargerStatusLog;
import com.evcsms.backend.repository.ChargerRepository;
import com.evcsms.backend.repository.ChargerStatusLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class ChargerUptimeService {

    private static final Logger logger = LoggerFactory.getLogger(ChargerUptimeService.class);

    private final ChargerStatusLogRepository statusLogRepository;
    private final ChargerRepository chargerRepository;

    public ChargerUptimeService(ChargerStatusLogRepository statusLogRepository,
                                ChargerRepository chargerRepository) {
        this.statusLogRepository = statusLogRepository;
        this.chargerRepository = chargerRepository;
    }

    @Transactional
    public void recordStatusChange(String ocppIdentity, String newStatus) {
        if (ocppIdentity == null || ocppIdentity.isBlank()) {
            return;
        }

        Charger charger = chargerRepository.findByOcppIdentity(ocppIdentity).orElse(null);
        if (charger == null) {
            logger.debug("Charger not found for ocppIdentity={}, uptime event skipped", ocppIdentity);
            return;
        }

        Optional<ChargerStatusLog> lastOpen = statusLogRepository
                .findTopByOcppIdentityAndEndedAtIsNullOrderByStartedAtDesc(ocppIdentity);

        if (lastOpen.isPresent() && newStatus.equals(lastOpen.get().getStatus())) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        lastOpen.ifPresent(openLog -> {
            openLog.setEndedAt(now);
            openLog.setDurationSeconds(ChronoUnit.SECONDS.between(openLog.getStartedAt(), now));
            statusLogRepository.save(openLog);
        });

        ChargerStatusLog log = new ChargerStatusLog();
        log.setChargerId(charger.getId());
        log.setChargerName(charger.getName());
        log.setOcppIdentity(ocppIdentity);
        log.setStationId(charger.getStationId());
        log.setStatus(newStatus);
        log.setStartedAt(now);
        statusLogRepository.save(log);

        logger.info("Charger uptime status recorded: ocppIdentity={}, status={}", ocppIdentity, newStatus);
    }

    @Transactional(readOnly = true)
    public List<ChargerUptimeSummary> getUptimeSummary(LocalDate fromDate, LocalDate toDate) {
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        List<ChargerStatusLog> allLogs = statusLogRepository
                .findByStartedAtBetweenOrderByChargerIdAscStartedAtAsc(from, to);

        Map<Long, ChargerUptimeSummary> summaryMap = new LinkedHashMap<>();

        for (ChargerStatusLog log : allLogs) {
            Long key = log.getChargerId();
            summaryMap.computeIfAbsent(key, k -> new ChargerUptimeSummary(
                    log.getChargerId(), log.getChargerName(), log.getOcppIdentity(), log.getStationId()
            ));

            long duration = effectiveDuration(log, now);
            ChargerUptimeSummary summary = summaryMap.get(key);
            accumulateDuration(summary, log.getStatus(), duration);
            summary.statusLogs.add(toEntry(log, duration));
        }

        return new ArrayList<>(summaryMap.values());
    }

    @Transactional(readOnly = true)
    public ChargerUptimeSummary getChargerUptime(Long chargerId, LocalDate fromDate, LocalDate toDate) {
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime to = toDate.plusDays(1).atStartOfDay();
        LocalDateTime now = LocalDateTime.now();

        List<ChargerStatusLog> logs = statusLogRepository
                .findByChargerIdAndStartedAtBetweenOrderByStartedAtAsc(chargerId, from, to);

        Charger charger = chargerRepository.findById(chargerId).orElse(null);
        ChargerUptimeSummary summary = new ChargerUptimeSummary(
                chargerId,
                charger != null ? charger.getName() : "Unknown",
                charger != null ? charger.getOcppIdentity() : null,
                charger != null ? charger.getStationId() : null
        );

        for (ChargerStatusLog log : logs) {
            long duration = effectiveDuration(log, now);
            accumulateDuration(summary, log.getStatus(), duration);
            summary.statusLogs.add(toEntry(log, duration));
        }

        return summary;
    }

    private long effectiveDuration(ChargerStatusLog log, LocalDateTime now) {
        if (log.getDurationSeconds() != null) {
            return log.getDurationSeconds();
        }
        LocalDateTime end = log.getEndedAt() != null ? log.getEndedAt() : now;
        return ChronoUnit.SECONDS.between(log.getStartedAt(), end);
    }

    private void accumulateDuration(ChargerUptimeSummary summary, String status, long seconds) {
        switch (status) {
            case "ONLINE"  -> summary.totalOnlineSeconds  += seconds;
            case "OFFLINE" -> summary.totalOfflineSeconds += seconds;
            case "FAULTED" -> summary.totalFaultedSeconds += seconds;
        }
    }

    private StatusLogEntry toEntry(ChargerStatusLog log, long duration) {
        return new StatusLogEntry(log.getId(), log.getStatus(), log.getStartedAt(), log.getEndedAt(), duration);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────────

    public static class ChargerUptimeSummary {
        public Long chargerId;
        public String chargerName;
        public String ocppIdentity;
        public Long stationId;
        public long totalOnlineSeconds;
        public long totalOfflineSeconds;
        public long totalFaultedSeconds;
        public List<StatusLogEntry> statusLogs = new ArrayList<>();

        public ChargerUptimeSummary(Long chargerId, String chargerName, String ocppIdentity, Long stationId) {
            this.chargerId = chargerId;
            this.chargerName = chargerName;
            this.ocppIdentity = ocppIdentity;
            this.stationId = stationId;
        }
    }

    public record StatusLogEntry(
            Long id,
            String status,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            long durationSeconds
    ) {}
}
