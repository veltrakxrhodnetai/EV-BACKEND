package com.evcsms.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "ocpp_configurations", schema = "backend")
public class OcppConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_point_identity", nullable = false)
    private String chargePointIdentity;

    @Column(name = "websocket_url", nullable = false)
    private String websocketUrl;

    @Column(name = "heartbeat_interval_seconds", nullable = false)
    private Integer heartbeatIntervalSeconds;

    @Column(name = "meter_value_interval_seconds", nullable = false)
    private Integer meterValueIntervalSeconds;

    @Column(name = "allowed_ips")
    private String allowedIps;

    @Column(name = "security_mode", nullable = false)
    private String securityMode;

    @Column(name = "token_value")
    private String tokenValue;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.active == null) {
            this.active = true;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getChargePointIdentity() {
        return chargePointIdentity;
    }

    public void setChargePointIdentity(String chargePointIdentity) {
        this.chargePointIdentity = chargePointIdentity;
    }

    public String getWebsocketUrl() {
        return websocketUrl;
    }

    public void setWebsocketUrl(String websocketUrl) {
        this.websocketUrl = websocketUrl;
    }

    public Integer getHeartbeatIntervalSeconds() {
        return heartbeatIntervalSeconds;
    }

    public void setHeartbeatIntervalSeconds(Integer heartbeatIntervalSeconds) {
        this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
    }

    public Integer getMeterValueIntervalSeconds() {
        return meterValueIntervalSeconds;
    }

    public void setMeterValueIntervalSeconds(Integer meterValueIntervalSeconds) {
        this.meterValueIntervalSeconds = meterValueIntervalSeconds;
    }

    public String getAllowedIps() {
        return allowedIps;
    }

    public void setAllowedIps(String allowedIps) {
        this.allowedIps = allowedIps;
    }

    public String getSecurityMode() {
        return securityMode;
    }

    public void setSecurityMode(String securityMode) {
        this.securityMode = securityMode;
    }

    public String getTokenValue() {
        return tokenValue;
    }

    public void setTokenValue(String tokenValue) {
        this.tokenValue = tokenValue;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
