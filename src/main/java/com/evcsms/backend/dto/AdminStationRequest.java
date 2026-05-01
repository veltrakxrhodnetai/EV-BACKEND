package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record AdminStationRequest(
        @NotBlank(message = "Station name is required")
        String name,

        String stationCode,

        @NotBlank(message = "Address is required")
        String address,

        @NotBlank(message = "City is required")
        String city,

        @NotBlank(message = "State is required")
        String state,

        String pincode,

        @NotNull(message = "Latitude is required")
        Double latitude,

        @NotNull(message = "Longitude is required")
        Double longitude,

        String operatingHoursType,
        String operatingHoursJson,
        String amenitiesJson,
        String supportContactNumber,
        String paymentMethodsJson,

        @NotBlank(message = "Status is required")
        String status
) {
}
