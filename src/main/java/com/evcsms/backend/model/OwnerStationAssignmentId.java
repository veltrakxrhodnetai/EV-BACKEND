package com.evcsms.backend.model;

import java.io.Serializable;
import java.util.Objects;

public class OwnerStationAssignmentId implements Serializable {
    private Long ownerId;
    private Long stationId;

    public OwnerStationAssignmentId() {
    }

    public OwnerStationAssignmentId(Long ownerId, Long stationId) {
        this.ownerId = ownerId;
        this.stationId = stationId;
    }

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        OwnerStationAssignmentId that = (OwnerStationAssignmentId) o;
        return Objects.equals(ownerId, that.ownerId) &&
                Objects.equals(stationId, that.stationId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId, stationId);
    }
}
