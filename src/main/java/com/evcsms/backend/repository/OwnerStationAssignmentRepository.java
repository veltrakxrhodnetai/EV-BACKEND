package com.evcsms.backend.repository;

import com.evcsms.backend.model.OwnerStationAssignment;
import com.evcsms.backend.model.OwnerStationAssignmentId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface OwnerStationAssignmentRepository extends JpaRepository<OwnerStationAssignment, OwnerStationAssignmentId> {
    
    /**
     * Find all stations assigned to an owner (with eager station loading)
     */
    @Query("SELECT DISTINCT a FROM OwnerStationAssignment a LEFT JOIN FETCH a.station WHERE a.ownerId = :ownerId")
    List<OwnerStationAssignment> findByOwnerId(@Param("ownerId") Long ownerId);
    
    /**
        * Find all owners assigned to a station
     */
    List<OwnerStationAssignment> findByStationId(Long stationId);
    
    /**
        * Check if an owner has access to a specific station
     */
        Optional<OwnerStationAssignment> findByOwnerIdAndStationId(Long ownerId, Long stationId);
    
    /**
     * Find assignment with specific role
     */
    @Query("SELECT o FROM OwnerStationAssignment o WHERE o.ownerId = :ownerId AND o.stationId = :stationId AND o.role = :role")
    Optional<OwnerStationAssignment> findByOwnerIdAndStationIdAndRole(
        @Param("ownerId") Long ownerId, 
        @Param("stationId") Long stationId, 
        @Param("role") String role
    );
    
    /**
     * Delete all assignments for an owner
     */
    @Modifying
    @Transactional
    long deleteByOwnerId(Long ownerId);

    @Modifying
    @Transactional
    long deleteByStationId(Long stationId);
}
