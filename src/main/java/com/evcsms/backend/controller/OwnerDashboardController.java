package com.evcsms.backend.controller;

import com.evcsms.backend.aspect.OwnerStationAccessAspect;
import com.evcsms.backend.repository.ChargingSessionRepository;
import com.evcsms.backend.repository.OwnerAccountRepository;
import com.evcsms.backend.service.OwnerAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/owner/dashboard")
@CrossOrigin(origins = "*")
public class OwnerDashboardController {

    private final ChargingSessionRepository chargingSessionRepository;
    private final OwnerAccountRepository ownerAccountRepository;
    private final OwnerStationAccessAspect ownerStationAccessAspect;
        private final JdbcTemplate jdbcTemplate;

    public OwnerDashboardController(
            ChargingSessionRepository chargingSessionRepository,
            OwnerAccountRepository ownerAccountRepository,
                        OwnerStationAccessAspect ownerStationAccessAspect,
                        JdbcTemplate jdbcTemplate
    ) {
        this.chargingSessionRepository = chargingSessionRepository;
        this.ownerAccountRepository = ownerAccountRepository;
        this.ownerStationAccessAspect = ownerStationAccessAspect;
                this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/{ownerId}/sessions")
    public ResponseEntity<?> getOwnerSessionsDashboard(
            @PathVariable Long ownerId,
            @RequestParam(required = false) LocalDate fromDate,
            @RequestParam(required = false) LocalDate toDate,
            @RequestParam(required = false) Long stationId,
            @RequestParam(required = false) Long chargerId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader
    ) {
        if (!ownerAccountRepository.existsById(ownerId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Owner not found: " + ownerId));
        }

        OwnerAuthService.AuthenticatedOwner requester = ownerStationAccessAspect.getOwnerIfPresent(authorizationHeader);
        if (requester != null && !requester.ownerId().equals(ownerId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied: cannot view another owner's dashboard"));
        }

        if (requester != null && stationId != null && !requester.canAccessStation(stationId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "Access denied: station is not assigned to owner"));
        }

        LocalDateTime fromDateTime = fromDate == null ? null : fromDate.atStartOfDay();
        LocalDateTime toDateTimeExclusive = toDate == null ? null : toDate.plusDays(1).atStartOfDay();

        List<Map<String, Object>> sessions = fetchOwnerDashboardRows(
            ownerId,
            fromDateTime,
            toDateTimeExclusive,
            stationId,
            chargerId
        );

        double totalRevenue = sessions.stream()
                .mapToDouble(s -> numberValue(s.get("ownerRevenue")))
                .sum();

        double totalEnergyUsed = sessions.stream()
                .mapToDouble(s -> numberValue(s.get("energyConsumedKwh")))
                .sum();

        List<Map<String, Object>> details = sessions.stream()
                .map(this::toSessionDetail)
                .toList();

        Map<String, Object> response = new LinkedHashMap<>();
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("fromDate", fromDate);
        filters.put("toDate", toDate);
        filters.put("stationId", stationId);
        filters.put("chargerId", chargerId);

        response.put("ownerId", ownerId);
        response.put("filters", filters);
        response.put("aggregated", Map.of(
                "totalRevenue", round2(totalRevenue),
                "totalSessions", sessions.size(),
                "totalEnergyUsed", round2(totalEnergyUsed)
        ));
        response.put("sessions", details);

        return ResponseEntity.ok(response);
    }

        private List<Map<String, Object>> fetchOwnerDashboardRows(
                        Long ownerId,
                        LocalDateTime fromDateTime,
                        LocalDateTime toDateTimeExclusive,
                        Long stationId,
                        Long chargerId
        ) {
                StringBuilder sql = new StringBuilder("""
                        SELECT
                                cs.id AS sessionId,
                                cs.status AS status,
                                cs.started_at AS startedAt,
                                cs.ended_at AS endedAt,
                                COALESCE(cs.owner_id, ch.owner_id) AS ownerId,
                                COALESCE(cs.station_id, st.id) AS stationId,
                                COALESCE(cs.charger_id, ch.id) AS chargerId,
                                st.name AS stationName,
                                ch.name AS chargerName,
                                ch.ocpp_identity AS chargerOcppIdentity,
                                cs.connector_no AS connectorNo,
                                cs.energy_consumed_kwh AS energyConsumedKwh,
                                cs.total_amount AS totalAmount,
                                cs.gst_amount AS gstAmount,
                                cs.base_amount AS baseAmount,
                                cs.platform_fee AS platformFee,
                                cs.owner_revenue AS ownerRevenue,
                                cs.payment_mode AS paymentMode,
                                cs.payment_status AS paymentStatus,
                                cs.vehicle_number AS vehicleNumber,
                                cs.phone_number AS phoneNumber,
                                COALESCE(cs.ended_at, cs.created_at) AS sortTimestamp
                        FROM backend.charging_sessions cs
                        JOIN backend.chargers ch ON ch.id = cs.charger_id
                        JOIN backend.stations st ON st.id = ch.station_id
                        WHERE (cs.owner_id = ? OR (cs.owner_id IS NULL AND ch.owner_id = ?))
                        """);

                List<Object> params = new ArrayList<>();
                params.add(ownerId);
                params.add(ownerId);

                if (fromDateTime != null) {
                        sql.append(" AND COALESCE(cs.ended_at, cs.created_at) >= ?");
                        params.add(Timestamp.valueOf(fromDateTime));
                }
                if (toDateTimeExclusive != null) {
                        sql.append(" AND COALESCE(cs.ended_at, cs.created_at) < ?");
                        params.add(Timestamp.valueOf(toDateTimeExclusive));
                }
                if (stationId != null) {
                        sql.append(" AND COALESCE(cs.station_id, st.id) = ?");
                        params.add(stationId);
                }
                if (chargerId != null) {
                        sql.append(" AND COALESCE(cs.charger_id, ch.id) = ?");
                        params.add(chargerId);
                }

                sql.append(" ORDER BY COALESCE(cs.ended_at, cs.created_at) DESC");

                return jdbcTemplate.queryForList(sql.toString(), params.toArray());
        }

        private Map<String, Object> toSessionDetail(Map<String, Object> session) {
        Map<String, Object> data = new LinkedHashMap<>();
                data.put("sessionId", longValue(session.get("sessionId")));
                data.put("status", stringValue(session.get("status")));
                data.put("startedAt", session.get("startedAt"));
                data.put("endedAt", session.get("endedAt"));

                data.put("ownerId", longValue(session.get("ownerId")));
                data.put("stationId", longValue(session.get("stationId")));
                data.put("chargerId", longValue(session.get("chargerId")));

                data.put("stationName", stringValue(session.get("stationName")));
                data.put("chargerName", stringValue(session.get("chargerName")));
                data.put("chargerOcppIdentity", stringValue(session.get("chargerOcppIdentity")));
                data.put("connectorNo", integerValue(session.get("connectorNo")));

                data.put("energyConsumedKwh", numberValue(session.get("energyConsumedKwh")));
                data.put("totalAmount", numberValue(session.get("totalAmount")));
                data.put("gstAmount", numberValue(session.get("gstAmount")));
                data.put("baseAmount", numberValue(session.get("baseAmount")));
                data.put("platformFee", numberValue(session.get("platformFee")));
                data.put("ownerRevenue", numberValue(session.get("ownerRevenue")));

                data.put("paymentMode", stringValue(session.get("paymentMode")));
                data.put("paymentStatus", stringValue(session.get("paymentStatus")));
                data.put("vehicleNumber", stringValue(session.get("vehicleNumber")));
                data.put("phoneNumber", stringValue(session.get("phoneNumber")));

        return data;
    }

        private double numberValue(Object value) {
                if (value instanceof Number number) {
                        return number.doubleValue();
                }
                return 0.0;
        }

        private Long longValue(Object value) {
                if (value instanceof Number number) {
                        return number.longValue();
                }
                return null;
        }

        private Integer integerValue(Object value) {
                if (value instanceof Number number) {
                        return number.intValue();
                }
                return null;
        }

        private String stringValue(Object value) {
                return value == null ? null : String.valueOf(value);
        }

    private double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
