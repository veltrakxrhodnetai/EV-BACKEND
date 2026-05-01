package com.evcsms.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "operator_station_assignments", schema = "backend")
@IdClass(OwnerStationAssignmentId.class)
public class OwnerStationAssignment {

    @Id
    @Column(name = "operator_id")
    private Long ownerId;

    @Id
    @Column(name = "station_id")
    private Long stationId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "operator_id", insertable = false, updatable = false)
    private OwnerAccount owner;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "station_id", insertable = false, updatable = false)
    private Station station;

    @Column(name = "role", nullable = false)
    private String role; // OWNER, TECHNICIAN, SUPERVISOR

    public OwnerStationAssignment() {
    }

    public OwnerStationAssignment(Long ownerId, Long stationId, String role) {
        this.ownerId = ownerId;
        this.stationId = stationId;
        this.role = role;
    }

    // Getters and Setters

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public OwnerAccount getOwner() {
        return owner;
    }

    public void setOwner(OwnerAccount owner) {
        this.owner = owner;
    }

    public Station getStation() {
        return station;
    }

    public void setStation(Station station) {
        this.station = station;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
