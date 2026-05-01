package com.evcsms.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public record AdminOwnerRequest(
        @NotBlank(message = "Owner name is required")
        String name,

        @NotBlank(message = "Mobile number is required")
        @Pattern(regexp = "^[0-9]{10,15}$", message = "Invalid mobile number")
        String mobile,

        String pin,
        String permissionsJson,
        List<Long> assignedStationIds,

        @NotBlank(message = "Status is required")
        String status
) {
}
