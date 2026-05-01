package com.evcsms.backend.controller;

import com.evcsms.backend.model.OwnerStationAssignment;
import com.evcsms.backend.service.OwnerAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/owner")
public class OwnerAuthController {

    private static final Logger logger = LoggerFactory.getLogger(OwnerAuthController.class);

        private final OwnerAuthService ownerAuthService;

        public OwnerAuthController(OwnerAuthService ownerAuthService) {
                this.ownerAuthService = ownerAuthService;
    }

    @PostMapping("/auth")
        public ResponseEntity<?> authenticate(@RequestBody OwnerAuthRequest request) {
        try {
                        logger.info("Owner authentication request for mobile: {}", request.mobileNumber());
                        OwnerAuthService.OwnerAuthResult result = ownerAuthService.authenticateOwner(
                    request.mobileNumber(),
                    request.pin()
            );
                        return ResponseEntity.ok(new OwnerAuthResponse(
                    result.token(),
                    result.tokenType(),
                    result.expiresInSeconds(),
                                        result.ownerId(),
                                        result.ownerName(),
                    result.assignedStations().stream()
                            .map(a -> new StationInfo(a.getStationId(), a.getRole()))
                            .toList()
            ));
        } catch (Exception ex) {
            logger.warn("Authentication failed: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse("Authentication failed: " + ex.getMessage()));
        }
    }

    // ==================== DTOs ====================

    public record OwnerAuthRequest(
            String mobileNumber,
            String pin
    ) {
    }

    public record OwnerAuthResponse(
            String token,
            String tokenType,
            long expiresInSeconds,
            Long ownerId,
            String ownerName,
            List<StationInfo> assignedStations
    ) {
    }

    public record StationInfo(
            Long stationId,
            String role
    ) {
    }

    public record ErrorResponse(
            String message
    ) {
    }
}
