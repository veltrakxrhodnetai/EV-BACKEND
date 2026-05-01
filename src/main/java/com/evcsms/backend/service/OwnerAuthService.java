package com.evcsms.backend.service;

import com.evcsms.backend.model.OwnerAccount;
import com.evcsms.backend.model.OwnerStationAssignment;
import com.evcsms.backend.repository.OwnerAccountRepository;
import com.evcsms.backend.repository.OwnerStationAssignmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
public class OwnerAuthService {

    private static final Logger logger = LoggerFactory.getLogger(OwnerAuthService.class);

    private static final long OWNER_JWT_TTL_SECONDS = 8 * 3600; // 8 hours
    private static final String OWNER_JWT_SECRET = "owner-dev-secret-change-before-production-very-long-key";

    private final OwnerAccountRepository ownerAccountRepository;
    private final OwnerStationAssignmentRepository ownerStationAssignmentRepository;

    public OwnerAuthService(OwnerAccountRepository ownerAccountRepository,
                               OwnerStationAssignmentRepository ownerStationAssignmentRepository) {
        this.ownerAccountRepository = ownerAccountRepository;
        this.ownerStationAssignmentRepository = ownerStationAssignmentRepository;
    }

    /**
     * Authenticate owner by mobile number and PIN
     * @param mobileNumber Owner's mobile number
     * @param pin Owner's PIN
     * @return AuthenticationResult with JWT token and assigned stations
     */
    public OwnerAuthResult authenticateOwner(String mobileNumber, String pin) {
        logger.info("Attempting to authenticate owner with mobile: {}", mobileNumber);

        // DEV ONLY: Accept test PIN 123456 for any owner
        if ("123456".equals(pin)) {
            logger.warn("DEV MODE: Accepting test PIN 123456 for mobile {}", mobileNumber);
        } else {
            // Find owner by mobile number
            OwnerAccount owner = ownerAccountRepository.findByMobileNumber(mobileNumber)
                    .orElseThrow(() -> {
                        logger.warn("Owner not found with mobile: {}", mobileNumber);
                        return new IllegalArgumentException("Invalid mobile number or PIN");
                    });

            // Verify PIN against stored hash
            String incomingHash = sha256(pin);
            if (!MessageDigest.isEqual(
                    incomingHash.getBytes(StandardCharsets.UTF_8),
                    owner.getPinOrPasswordHash().getBytes(StandardCharsets.UTF_8)
            )) {
                logger.warn("Invalid PIN provided for mobile: {}", mobileNumber);
                throw new IllegalArgumentException("Invalid mobile number or PIN");
            }

            // Verify owner status
            if (!"ACTIVE".equalsIgnoreCase(owner.getStatus())) {
                logger.warn("Owner account is inactive: {}", mobileNumber);
                throw new IllegalArgumentException("Owner account is inactive");
            }
        }

        // Fetch owner
        OwnerAccount owner = ownerAccountRepository.findByMobileNumber(mobileNumber)
                .orElseThrow(() -> new IllegalArgumentException("Owner not found"));

        // Fetch assigned stations
        List<OwnerStationAssignment> assignments = ownerStationAssignmentRepository.findByOwnerId(owner.getId());
        if (assignments.isEmpty()) {
            logger.warn("Owner {} has no assigned stations", mobileNumber);
            throw new IllegalArgumentException("No stations assigned to owner");
        }

        // Generate JWT token
        String token = generateJwt(owner.getId(), owner.getMobileNumber(), owner.getName(), assignments, OWNER_JWT_TTL_SECONDS);
        logger.info("Successfully authenticated owner: {} with {} assigned stations", mobileNumber, assignments.size());

        return new OwnerAuthResult(token, "Bearer", OWNER_JWT_TTL_SECONDS, owner.getId(), owner.getName(), assignments);
    }

    /**
     * Verify if an owner has access to a specific station
     */
    public boolean hasAccessToStation(Long ownerId, Long stationId) {
        return ownerStationAssignmentRepository.findByOwnerIdAndStationId(ownerId, stationId).isPresent();
    }

    /**
     * Parse and validate JWT token from Authorization header
     */
    public AuthenticatedOwner requireOwnerFromAuthorizationHeader(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing Authorization header");
        }
        String token = authorizationHeader.substring(7);
        return parseAndValidate(token);
    }

    /**
    * Generate JWT token for owner with station assignments
     */
    private String generateJwt(Long ownerId, String mobileNumber, String name,
                                List<OwnerStationAssignment> assignments, long ttlSeconds) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + ttlSeconds;

        StringBuilder stationIdsJson = new StringBuilder("[");
        StringBuilder rolesJson = new StringBuilder("{");
        for (int i = 0; i < assignments.size(); i++) {
            OwnerStationAssignment a = assignments.get(i);
            if (i > 0) {
                stationIdsJson.append(",");
                rolesJson.append(",");
            }
            stationIdsJson.append(a.getStationId());
            rolesJson.append("\"").append(a.getStationId()).append("\":\"").append(a.getRole()).append("\"");
        }
        stationIdsJson.append("]");
        rolesJson.append("}");

        String payloadJson = String.format(
            "{\"sub\":\"%d\",\"mobile\":\"%s\",\"name\":\"%s\",\"iat\":%d,\"exp\":%d,\"stations\":%s,\"roles\":%s,\"type\":\"owner\"}",
            ownerId, mobileNumber, name, issuedAt, expiresAt, stationIdsJson, rolesJson
        );

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String encodedHeader = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;

        String signature = sign(signingInput, OWNER_JWT_SECRET);
        return signingInput + "." + signature;
    }

    /**
     * Parse and validate JWT token
     */
    private AuthenticatedOwner parseAndValidate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid token format");
        }

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput, OWNER_JWT_SECRET);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid token signature");
        }

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String ownerId = extractJsonValue(payloadJson, "sub");
        String mobileNumber = extractJsonValue(payloadJson, "mobile");
        String name = extractJsonValue(payloadJson, "name");
        String exp = extractJsonValue(payloadJson, "exp");
        String stationsStr = extractJsonValue(payloadJson, "stations");
        String rolesStr = extractJsonValue(payloadJson, "roles");

        if (ownerId == null || mobileNumber == null || exp == null) {
            throw new IllegalArgumentException("Token payload invalid");
        }

        long expSeconds = Long.parseLong(exp);
        if (Instant.now().getEpochSecond() > expSeconds) {
            throw new IllegalArgumentException("Token expired");
        }

        // Parse stations and roles from JSON
        List<Long> stationIds = parseJsonArray(stationsStr);
        java.util.Map<Long, String> roleMap = parseJsonObject(rolesStr);

        return new AuthenticatedOwner(Long.parseLong(ownerId), mobileNumber, name, stationIds, roleMap);
    }

    private List<Long> parseJsonArray(String json) {
        List<Long> result = new java.util.ArrayList<>();
        if (json == null || json.length() <= 2) return result;
        
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) return result;
        
        for (String item : content.split(",")) {
            result.add(Long.parseLong(item.trim()));
        }
        return result;
    }

    private java.util.Map<Long, String> parseJsonObject(String json) {
        java.util.Map<Long, String> result = new java.util.HashMap<>();
        if (json == null || json.length() <= 2) return result;
        
        String content = json.substring(1, json.length() - 1).trim();
        if (content.isEmpty()) return result;
        
        for (String item : content.split(",")) {
            String[] parts = item.split(":");
            if (parts.length == 2) {
                Long key = Long.parseLong(parts[0].trim().replaceAll("\"", ""));
                String value = parts[1].trim().replaceAll("\"", "");
                result.put(key, value);
            }
        }
        return result;
    }

    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIdx = json.indexOf(searchKey);
        if (startIdx == -1) return null;

        startIdx += searchKey.length();
        while (startIdx < json.length() && (json.charAt(startIdx) == ' ' || json.charAt(startIdx) == '\"')) {
            startIdx++;
        }

        int endIdx = startIdx;
        char startChar = json.charAt(startIdx - 1);
        if (startChar == '"') {
            while (endIdx < json.length() && json.charAt(endIdx) != '"') {
                endIdx++;
            }
        } else {
            while (endIdx < json.length() && json.charAt(endIdx) != ',' && json.charAt(endIdx) != '}') {
                endIdx++;
            }
        }

        return json.substring(startIdx, endIdx).trim();
    }

    private String sign(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return base64UrlEncode(signatureBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash password", ex);
        }
    }

    private String base64UrlEncode(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    // ==================== DTOs ====================

        public record OwnerAuthResult(
            String token,
            String tokenType,
            long expiresInSeconds,
            Long ownerId,
            String ownerName,
            List<OwnerStationAssignment> assignedStations
    ) {
    }

        public record AuthenticatedOwner(
            Long ownerId,
            String mobileNumber,
            String name,
            List<Long> stationIds,
            java.util.Map<Long, String> roleByStation
    ) {
        public String getRole(Long stationId) {
            return roleByStation.getOrDefault(stationId, "");
        }

        public boolean canAccessStation(Long stationId) {
            return stationIds.contains(stationId);
        }
    }
}
