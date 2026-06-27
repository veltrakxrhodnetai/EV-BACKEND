package com.evcsms.backend.controller;

import com.evcsms.backend.service.AdminAuthService;
import com.evcsms.backend.service.ChargerUptimeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/admin/uptime")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ChargerUptimeController {

    private final AdminAuthService adminAuthService;
    private final ChargerUptimeService chargerUptimeService;

    public ChargerUptimeController(AdminAuthService adminAuthService,
                                   ChargerUptimeService chargerUptimeService) {
        this.adminAuthService = adminAuthService;
        this.chargerUptimeService = chargerUptimeService;
    }

    @GetMapping("/summary")
    public List<ChargerUptimeService.ChargerUptimeSummary> getUptimeSummary(
            @RequestHeader("Authorization") String authorization,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        requireAdmin(authorization);
        return chargerUptimeService.getUptimeSummary(from, to);
    }

    @GetMapping("/{chargerId}/timeline")
    public ChargerUptimeService.ChargerUptimeSummary getChargerTimeline(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        requireAdmin(authorization);
        return chargerUptimeService.getChargerUptime(chargerId, from, to);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> exportAllCsv(
            @RequestHeader("Authorization") String authorization,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        requireAdmin(authorization);
        List<ChargerUptimeService.ChargerUptimeSummary> summaries = chargerUptimeService.getUptimeSummary(from, to);
        String csv = buildSummaryCsv(summaries);
        return csvResponse(csv, "charger_uptime_" + from + "_to_" + to + ".csv");
    }

    @GetMapping("/{chargerId}/export")
    public ResponseEntity<byte[]> exportChargerCsv(
            @RequestHeader("Authorization") String authorization,
            @PathVariable Long chargerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        requireAdmin(authorization);
        ChargerUptimeService.ChargerUptimeSummary summary = chargerUptimeService.getChargerUptime(chargerId, from, to);
        String csv = buildTimelineCsv(summary);
        return csvResponse(csv, "charger_" + chargerId + "_uptime_" + from + "_to_" + to + ".csv");
    }

    private String buildSummaryCsv(List<ChargerUptimeService.ChargerUptimeSummary> summaries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Charger ID,Charger Name,OCPP Identity,Station ID,Online Hours,Offline Hours,Faulted Hours,Online %\r\n");
        for (ChargerUptimeService.ChargerUptimeSummary s : summaries) {
            long total = s.totalOnlineSeconds + s.totalOfflineSeconds + s.totalFaultedSeconds;
            double onlinePct = total > 0 ? (s.totalOnlineSeconds * 100.0 / total) : 0.0;
            sb.append(s.chargerId).append(",")
              .append(escapeCsv(s.chargerName)).append(",")
              .append(escapeCsv(s.ocppIdentity)).append(",")
              .append(s.stationId != null ? s.stationId : "").append(",")
              .append(String.format("%.2f", s.totalOnlineSeconds / 3600.0)).append(",")
              .append(String.format("%.2f", s.totalOfflineSeconds / 3600.0)).append(",")
              .append(String.format("%.2f", s.totalFaultedSeconds / 3600.0)).append(",")
              .append(String.format("%.1f", onlinePct)).append("\r\n");
        }
        return sb.toString();
    }

    private String buildTimelineCsv(ChargerUptimeService.ChargerUptimeSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("Charger: ").append(escapeCsv(summary.chargerName))
          .append(" (").append(escapeCsv(summary.ocppIdentity)).append(")\r\n");
        sb.append("Status,Started At,Ended At,Duration (minutes)\r\n");
        for (ChargerUptimeService.StatusLogEntry e : summary.statusLogs) {
            sb.append(e.status()).append(",")
              .append(e.startedAt() != null ? e.startedAt() : "").append(",")
              .append(e.endedAt() != null ? e.endedAt() : "In Progress").append(",")
              .append(String.format("%.1f", e.durationSeconds() / 60.0)).append("\r\n");
        }
        return sb.toString();
    }

    private ResponseEntity<byte[]> csvResponse(String csv, String filename) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(bytes);
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void requireAdmin(String authorizationHeader) {
        try {
            AdminAuthService.AuthenticatedAdmin admin =
                    adminAuthService.requireAdminFromAuthorizationHeader(authorizationHeader);
            adminAuthService.requireRole(admin, "ADMIN", "SUPER_ADMIN");
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }
}
