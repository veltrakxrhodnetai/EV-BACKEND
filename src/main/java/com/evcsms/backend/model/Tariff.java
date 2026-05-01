package com.evcsms.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "tariffs", schema = "backend")
public class Tariff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "station_id", nullable = false)
    private Station station;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "charger_id")
    private Charger charger;

    @Column(name = "price_per_kwh", nullable = false)
    private Double pricePerKwh;

    @Column(name = "gst_percent", nullable = false)
    private Double gstPercent = 18.0;

    @Column(name = "session_fee", nullable = false)
    private Double sessionFee = 0.0;

    @Column(name = "idle_fee")
    private Double idleFee = 0.0;

    @Column(name = "time_fee")
    private Double timeFee = 0.0;

    @Column(name = "platform_fee_percent", nullable = false)
    private Double platformFeePercent = 12.0;

    @Column(nullable = false)
    private String currency = "INR";

    @Column(name = "effective_from")
    private LocalDateTime effectiveFrom;

    @Column(name = "effective_to")
    private LocalDateTime effectiveTo;

    @Column(name = "scope_type")
    private String scopeType;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Station getStation() {
        return station;
    }

    public void setStation(Station station) {
        this.station = station;
    }

    public Charger getCharger() {
        return charger;
    }

    public void setCharger(Charger charger) {
        this.charger = charger;
    }

    public Double getPricePerKwh() {
        return pricePerKwh;
    }

    public void setPricePerKwh(Double pricePerKwh) {
        this.pricePerKwh = pricePerKwh;
    }

    public Double getGstPercent() {
        return gstPercent;
    }

    public void setGstPercent(Double gstPercent) {
        this.gstPercent = gstPercent;
    }

    public Double getSessionFee() {
        return sessionFee;
    }

    public void setSessionFee(Double sessionFee) {
        this.sessionFee = sessionFee;
    }

    public Double getIdleFee() {
        return idleFee;
    }

    public void setIdleFee(Double idleFee) {
        this.idleFee = idleFee;
    }

    public Double getTimeFee() {
        return timeFee;
    }

    public void setTimeFee(Double timeFee) {
        this.timeFee = timeFee;
    }

    public Double getPlatformFeePercent() {
        return platformFeePercent;
    }

    public void setPlatformFeePercent(Double platformFeePercent) {
        this.platformFeePercent = platformFeePercent;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public LocalDateTime getEffectiveFrom() {
        return effectiveFrom;
    }

    public void setEffectiveFrom(LocalDateTime effectiveFrom) {
        this.effectiveFrom = effectiveFrom;
    }

    public LocalDateTime getEffectiveTo() {
        return effectiveTo;
    }

    public void setEffectiveTo(LocalDateTime effectiveTo) {
        this.effectiveTo = effectiveTo;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }
}
