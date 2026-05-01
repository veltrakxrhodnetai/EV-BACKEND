package com.evcsms.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "charging_sessions", schema = "backend")
public class ChargingSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "charger_id", nullable = false)
    private Charger charger;

    @Column(name = "charger_id", insertable = false, updatable = false)
    private Long chargerId;

    @Column(name = "station_id")
    private Long stationId;

    @Column(name = "owner_id")
    private Long ownerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "connector_id", nullable = false)
    private Connector connector;

    @Column(name = "connector_no", nullable = false)
    private Integer connectorNo;

    @Column(name = "ocpp_transaction_id")
    private Integer ocppTransactionId;

    @Column(name = "vehicle_number")
    private String vehicleNumber;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(name = "started_by", nullable = false)
    private String startedBy;

    @Column(name = "limit_type")
    private String limitType;

    @Column(name = "limit_value")
    private Double limitValue;

    @Column(name = "meter_start")
    private Long meterStart;

    @Column(name = "meter_stop")
    private Long meterStop;

    @Column(name = "energy_consumed_kwh")
    private Double energyConsumedKwh;

    @Column(name = "base_amount")
    private Double baseAmount;

    @Column(name = "gst_amount")
    private Double gstAmount;

    @Column(name = "total_amount")
    private Double totalAmount;

    @Column(name = "platform_fee")
    private Double platformFee;

    @Column(name = "owner_revenue")
    private Double ownerRevenue;

    @Column(name = "payment_mode", nullable = false)
    private String paymentMode;

    @Column(name = "payment_status", nullable = false)
    private String paymentStatus;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "connector_verified")
    private Boolean connectorVerified = false;

    @Column(name = "connector_verified_at")
    private LocalDateTime connectorVerifiedAt;

    @Column(name = "preauth_id")
    private String preauthId;

    @Column(name = "preauth_amount")
    private Double preauthAmount;

    @Column(name = "refund_amount")
    private Double refundAmount = 0.0;

    @Column(name = "refund_id")
    private String refundId;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "invoice_url")
    private String invoiceUrl;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
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

    public Charger getCharger() {
        return charger;
    }

    public void setCharger(Charger charger) {
        this.charger = charger;
        this.chargerId = charger == null ? null : charger.getId();
        this.stationId = charger == null ? null : charger.getStationId();
        this.ownerId = charger == null ? null : charger.getOwnerId();
    }

    public Long getChargerId() {
        return chargerId;
    }

    public void setChargerId(Long chargerId) {
        this.chargerId = chargerId;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public Connector getConnector() {
        return connector;
    }

    public void setConnector(Connector connector) {
        this.connector = connector;
    }

    public Integer getConnectorNo() {
        return connectorNo;
    }

    public void setConnectorNo(Integer connectorNo) {
        this.connectorNo = connectorNo;
    }

    public Integer getOcppTransactionId() {
        return ocppTransactionId;
    }

    public void setOcppTransactionId(Integer ocppTransactionId) {
        this.ocppTransactionId = ocppTransactionId;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public void setVehicleNumber(String vehicleNumber) {
        this.vehicleNumber = vehicleNumber;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public void setStartedBy(String startedBy) {
        this.startedBy = startedBy;
    }

    public String getLimitType() {
        return limitType;
    }

    public void setLimitType(String limitType) {
        this.limitType = limitType;
    }

    public Double getLimitValue() {
        return limitValue;
    }

    public void setLimitValue(Double limitValue) {
        this.limitValue = limitValue;
    }

    public Long getMeterStart() {
        return meterStart;
    }

    public void setMeterStart(Long meterStart) {
        this.meterStart = meterStart;
    }

    public Long getMeterStop() {
        return meterStop;
    }

    public void setMeterStop(Long meterStop) {
        this.meterStop = meterStop;
    }

    public Double getEnergyConsumedKwh() {
        return energyConsumedKwh;
    }

    public void setEnergyConsumedKwh(Double energyConsumedKwh) {
        this.energyConsumedKwh = energyConsumedKwh;
    }

    public Double getBaseAmount() {
        return baseAmount;
    }

    public void setBaseAmount(Double baseAmount) {
        this.baseAmount = baseAmount;
    }

    public Double getGstAmount() {
        return gstAmount;
    }

    public void setGstAmount(Double gstAmount) {
        this.gstAmount = gstAmount;
    }

    public Double getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(Double totalAmount) {
        this.totalAmount = totalAmount;
    }

    public Double getPlatformFee() {
        return platformFee;
    }

    public void setPlatformFee(Double platformFee) {
        this.platformFee = platformFee;
    }

    public Double getOwnerRevenue() {
        return ownerRevenue;
    }

    public void setOwnerRevenue(Double ownerRevenue) {
        this.ownerRevenue = ownerRevenue;
    }

    public String getPaymentMode() {
        return paymentMode;
    }

    public void setPaymentMode(String paymentMode) {
        this.paymentMode = paymentMode;
    }

    public String getPaymentStatus() {
        return paymentStatus;
    }

    public void setPaymentStatus(String paymentStatus) {
        this.paymentStatus = paymentStatus;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Boolean getConnectorVerified() {
        return connectorVerified;
    }

    public void setConnectorVerified(Boolean connectorVerified) {
        this.connectorVerified = connectorVerified;
    }

    public LocalDateTime getConnectorVerifiedAt() {
        return connectorVerifiedAt;
    }

    public void setConnectorVerifiedAt(LocalDateTime connectorVerifiedAt) {
        this.connectorVerifiedAt = connectorVerifiedAt;
    }

    public String getPreauthId() {
        return preauthId;
    }

    public void setPreauthId(String preauthId) {
        this.preauthId = preauthId;
    }

    public Double getPreauthAmount() {
        return preauthAmount;
    }

    public void setPreauthAmount(Double preauthAmount) {
        this.preauthAmount = preauthAmount;
    }

    public Double getRefundAmount() {
        return refundAmount;
    }

    public void setRefundAmount(Double refundAmount) {
        this.refundAmount = refundAmount;
    }

    public String getRefundId() {
        return refundId;
    }

    public void setRefundId(String refundId) {
        this.refundId = refundId;
    }

    public String getInvoiceNumber() {
        return invoiceNumber;
    }

    public void setInvoiceNumber(String invoiceNumber) {
        this.invoiceNumber = invoiceNumber;
    }

    public String getInvoiceUrl() {
        return invoiceUrl;
    }

    public void setInvoiceUrl(String invoiceUrl) {
        this.invoiceUrl = invoiceUrl;
    }
}
