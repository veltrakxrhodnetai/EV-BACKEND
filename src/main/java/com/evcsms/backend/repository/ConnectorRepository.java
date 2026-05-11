package com.evcsms.backend.repository;

import com.evcsms.backend.model.Connector;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConnectorRepository extends JpaRepository<Connector, Long> {

        @Query("""
                SELECT c
                FROM Connector c
                WHERE c.charger.id = :chargerId
                    AND UPPER(c.status) <> 'DELETED'
                ORDER BY c.connectorNo ASC
                """)
        List<Connector> findByCharger_Id(@Param("chargerId") Long chargerId);

        @Query("""
                SELECT c
                FROM Connector c
                WHERE c.charger.id = :chargerId
                    AND c.connectorNo = :connectorNo
                    AND UPPER(c.status) <> 'DELETED'
                """)
        Optional<Connector> findByCharger_IdAndConnectorNo(@Param("chargerId") Long chargerId, @Param("connectorNo") Integer connectorNo);

    @Modifying
    @Transactional
    @Query("UPDATE Connector c SET c.status = :status WHERE c.id = :connectorId")
    void updateStatusById(@Param("connectorId") Long connectorId, @Param("status") String status);

    @Modifying
    @Transactional
    long deleteByCharger_Id(Long chargerId);
}
