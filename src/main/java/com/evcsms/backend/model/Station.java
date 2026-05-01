package com.evcsms.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "stations", schema = "backend")
public class Station {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "station_code", unique = true)
    private String stationCode;

    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false)
    private String state;

    @Column
    private String pincode;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(nullable = false)
    private String status;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "operating_hours_type")
    private String operatingHoursType;

    @Column(name = "operating_hours_json")
    private String operatingHoursJson;

    @Column(name = "amenities_json")
    private String amenitiesJson;

    @Column(name = "support_contact_number")
    private String supportContactNumber;

    @Column(name = "payment_methods_json")
    private String paymentMethodsJson;

    @Column(name = "map_embed_html")
    private String mapEmbedHtml;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getAddress() {
        return address;
    }

    public String getStationCode() {
        return stationCode;
    }

    public void setStationCode(String stationCode) {
        this.stationCode = stationCode;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getPincode() {
        return pincode;
    }

    public void setPincode(String pincode) {
        this.pincode = pincode;
    }

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOperatingHoursType() {
        return operatingHoursType;
    }

    public void setOperatingHoursType(String operatingHoursType) {
        this.operatingHoursType = operatingHoursType;
    }

    public String getOperatingHoursJson() {
        return operatingHoursJson;
    }

    public void setOperatingHoursJson(String operatingHoursJson) {
        this.operatingHoursJson = operatingHoursJson;
    }

    public String getAmenitiesJson() {
        return amenitiesJson;
    }

    public void setAmenitiesJson(String amenitiesJson) {
        this.amenitiesJson = amenitiesJson;
    }

    public String getSupportContactNumber() {
        return supportContactNumber;
    }

    public void setSupportContactNumber(String supportContactNumber) {
        this.supportContactNumber = supportContactNumber;
    }

    public String getPaymentMethodsJson() {
        return paymentMethodsJson;
    }

    public void setPaymentMethodsJson(String paymentMethodsJson) {
        this.paymentMethodsJson = paymentMethodsJson;
    }

    public String getMapEmbedHtml() {
        return mapEmbedHtml;
    }

    public void setMapEmbedHtml(String mapEmbedHtml) {
        this.mapEmbedHtml = mapEmbedHtml;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
