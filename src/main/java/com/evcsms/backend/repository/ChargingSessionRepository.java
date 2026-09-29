package com.evcsms.backend.repository;

import com.evcsms.backend.model.ChargingSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChargingSessionRepository extends JpaRepository<ChargingSession, Long> {

    @Query("SELECT cs FROM ChargingSession cs JOIN FETCH cs.charger ch JOIN FETCH ch.station st LEFT JOIN FETCH cs.connector WHERE cs.ocppTransactionId = :txId")
    Optional<ChargingSession> findByOcppTransactionId(@Param("txId") Integer txId);

    @Query("SELECT cs FROM ChargingSession cs JOIN FETCH cs.charger ch JOIN FETCH ch.station JOIN FETCH cs.connector WHERE cs.id = :id")
    Optional<ChargingSession> findByIdWithChargerAndConnector(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT cs FROM ChargingSession cs WHERE cs.id = :id")
    Optional<ChargingSession> findByIdForUpdate(@Param("id") Long id);

    /** Atomically moves a session between statuses; returns 0 if the session was no longer in {@code fromStatus}. */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE ChargingSession cs SET cs.status = :toStatus, cs.updatedAt = :now WHERE cs.id = :id AND cs.status = :fromStatus")
    int transitionStatus(@Param("id") Long id,
                         @Param("fromStatus") String fromStatus,
                         @Param("toStatus") String toStatus,
                         @Param("now") LocalDateTime now);

    List<ChargingSession> findByStatusAndEndedAtBefore(String status, LocalDateTime cutoff);

    List<ChargingSession> findByStatusAndOcppTransactionIdIsNullAndUpdatedAtBefore(String status, LocalDateTime cutoff);

    List<ChargingSession> findByStatusAndPaymentStatusAndUpdatedAtBefore(String status, String paymentStatus, LocalDateTime cutoff);

    Optional<ChargingSession> findByCharger_IdAndConnector_IdAndStatus(Long chargerId, Long connectorId, String status);

    @Query("SELECT cs FROM ChargingSession cs JOIN FETCH cs.charger ch JOIN FETCH ch.station st LEFT JOIN FETCH cs.connector WHERE ch.id = :chargerId AND cs.connectorNo = :connectorNo AND cs.status = :status ORDER BY cs.createdAt DESC")
    Optional<ChargingSession> findFirstByCharger_IdAndConnectorNoAndStatusOrderByCreatedAtDesc(@Param("chargerId") Long chargerId, @Param("connectorNo") Integer connectorNo, @Param("status") String status);

    Optional<ChargingSession> findFirstByCharger_OcppIdentityAndStatusInOrderByCreatedAtDesc(String ocppIdentity, Collection<String> statuses);

    List<ChargingSession> findByStatusInOrderByCreatedAtDesc(Collection<String> statuses);

    @Query("SELECT cs FROM ChargingSession cs JOIN FETCH cs.charger c WHERE cs.status IN :statuses ORDER BY cs.createdAt DESC")
    List<ChargingSession> findByStatusInWithChargerOrderByCreatedAtDesc(@Param("statuses") Collection<String> statuses);

    boolean existsByCharger_IdAndConnectorNoAndStatusIn(Long chargerId, Integer connectorNo, Collection<String> statuses);

    boolean existsByConnector_Id(Long connectorId);

    boolean existsByCharger_Id(Long chargerId);

    boolean existsByStationId(Long stationId);

    boolean existsByPhoneNumber(String phoneNumber);

    Optional<ChargingSession> findFirstByOcppTransactionIdOrderByCreatedAtDesc(Integer ocppTransactionId);

        @Query("SELECT cs FROM ChargingSession cs JOIN FETCH cs.charger ch JOIN FETCH ch.station st LEFT JOIN FETCH cs.connector WHERE cs.phoneNumber = :phoneNumber AND cs.status IN :statuses ORDER BY cs.createdAt DESC")
        List<ChargingSession> findByPhoneNumberAndStatusInOrderByCreatedAtDesc(
            @Param("phoneNumber") String phoneNumber,
            @Param("statuses") Collection<String> statuses
        );

    List<ChargingSession> findTop20ByPhoneNumberOrderByCreatedAtDesc(String phoneNumber);

    @Query("SELECT cs FROM ChargingSession cs LEFT JOIN FETCH cs.charger ch LEFT JOIN FETCH ch.station WHERE cs.phoneNumber = :phoneNumber ORDER BY cs.createdAt DESC")
    List<ChargingSession> findHistoryByPhoneNumber(@Param("phoneNumber") String phoneNumber, org.springframework.data.domain.Pageable pageable);

    Long countByStatus(String status);

    Long countByStatusAndCreatedAtBetween(String status, LocalDateTime from, LocalDateTime to);

    @Query("SELECT COALESCE(SUM(cs.totalAmount), 0) FROM ChargingSession cs WHERE cs.createdAt BETWEEN :from AND :to")
    Double sumRevenueBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("SELECT COALESCE(MAX(cs.ocppTransactionId), 0) FROM ChargingSession cs")
    Integer findMaxOcppTransactionId();

    @Query("SELECT c.ocppIdentity FROM ChargingSession cs JOIN cs.charger c WHERE cs.id = :sessionId")
    Optional<String> findChargerOcppIdentityBySessionId(@Param("sessionId") Long sessionId);

    @Query("SELECT c.id FROM ChargingSession cs JOIN cs.connector c WHERE cs.id = :sessionId")
    Optional<Long> findConnectorIdBySessionId(@Param("sessionId") Long sessionId);

    @Query("SELECT c.chargerType FROM ChargingSession cs JOIN cs.charger c WHERE cs.id = :sessionId")
    Optional<String> findChargerTypeBySessionId(@Param("sessionId") Long sessionId);

    @Query("SELECT c.type FROM ChargingSession cs JOIN cs.connector c WHERE cs.id = :sessionId")
    Optional<String> findConnectorTypeBySessionId(@Param("sessionId") Long sessionId);

        @Query("""
                        SELECT COALESCE(SUM(cs.ownerRevenue), 0)
                        FROM ChargingSession cs
                        JOIN cs.charger ch
                        WHERE (cs.ownerId = :ownerId OR (cs.ownerId IS NULL AND ch.ownerId = :ownerId))
                            AND cs.ownerRevenue IS NOT NULL
                        """)
        Double sumOwnerRevenueByOwnerId(@Param("ownerId") Long ownerId);

        @Query("""
            SELECT cs
            FROM ChargingSession cs
            JOIN FETCH cs.charger ch
            JOIN FETCH ch.station st
            LEFT JOIN FETCH cs.connector cn
            WHERE (cs.ownerId = :ownerId OR (cs.ownerId IS NULL AND ch.ownerId = :ownerId))
              AND (:fromDate IS NULL OR COALESCE(cs.endedAt, cs.createdAt) >= :fromDate)
              AND (:toDate IS NULL OR COALESCE(cs.endedAt, cs.createdAt) < :toDate)
              AND (:stationId IS NULL OR COALESCE(cs.stationId, st.id) = :stationId)
              AND (:chargerId IS NULL OR COALESCE(cs.chargerId, ch.id) = :chargerId)
            ORDER BY COALESCE(cs.endedAt, cs.createdAt) DESC
            """)
        List<ChargingSession> findOwnerDashboardSessions(
            @Param("ownerId") Long ownerId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("stationId") Long stationId,
            @Param("chargerId") Long chargerId
        );

        @Query("""
            SELECT
              cs.id AS sessionId,
              cs.status AS status,
              cs.startedAt AS startedAt,
              cs.endedAt AS endedAt,
              COALESCE(cs.ownerId, ch.ownerId) AS ownerId,
              COALESCE(cs.stationId, st.id) AS stationId,
              COALESCE(cs.chargerId, ch.id) AS chargerId,
              st.name AS stationName,
              ch.name AS chargerName,
              ch.ocppIdentity AS chargerOcppIdentity,
              cs.connectorNo AS connectorNo,
              cs.energyConsumedKwh AS energyConsumedKwh,
              cs.totalAmount AS totalAmount,
              cs.gstAmount AS gstAmount,
              cs.baseAmount AS baseAmount,
              cs.platformFee AS platformFee,
              cs.ownerRevenue AS ownerRevenue,
              cs.paymentMode AS paymentMode,
              cs.paymentStatus AS paymentStatus,
              cs.vehicleNumber AS vehicleNumber,
              cs.phoneNumber AS phoneNumber
            FROM ChargingSession cs
            JOIN cs.charger ch
            JOIN ch.station st
            WHERE (cs.ownerId = :ownerId OR (cs.ownerId IS NULL AND ch.ownerId = :ownerId))
              AND (:fromDate IS NULL OR COALESCE(cs.endedAt, cs.createdAt) >= :fromDate)
              AND (:toDate IS NULL OR COALESCE(cs.endedAt, cs.createdAt) < :toDate)
              AND (:stationId IS NULL OR COALESCE(cs.stationId, st.id) = :stationId)
              AND (:chargerId IS NULL OR COALESCE(cs.chargerId, ch.id) = :chargerId)
            ORDER BY COALESCE(cs.endedAt, cs.createdAt) DESC
            """)
        List<OwnerDashboardRow> findOwnerDashboardRows(
            @Param("ownerId") Long ownerId,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            @Param("stationId") Long stationId,
            @Param("chargerId") Long chargerId
        );

        interface OwnerDashboardRow {
            Long getSessionId();
            String getStatus();
            LocalDateTime getStartedAt();
            LocalDateTime getEndedAt();
            Long getOwnerId();
            Long getStationId();
            Long getChargerId();
            String getStationName();
            String getChargerName();
            String getChargerOcppIdentity();
            Integer getConnectorNo();
            Double getEnergyConsumedKwh();
            Double getTotalAmount();
            Double getGstAmount();
            Double getBaseAmount();
            Double getPlatformFee();
            Double getOwnerRevenue();
            String getPaymentMode();
            String getPaymentStatus();
            String getVehicleNumber();
            String getPhoneNumber();
        }

            @Query("""
                SELECT cs
                FROM ChargingSession cs
                JOIN FETCH cs.charger ch
                JOIN FETCH ch.station st
                WHERE cs.status = 'COMPLETED'
                  AND cs.paymentStatus IN :paymentStatuses
                ORDER BY COALESCE(cs.endedAt, cs.createdAt) DESC
                """)
            List<ChargingSession> findCompletedFinancialSessions(@Param("paymentStatuses") Collection<String> paymentStatuses);
}
