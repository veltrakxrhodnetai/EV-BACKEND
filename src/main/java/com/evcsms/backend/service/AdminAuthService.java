package com.evcsms.backend.service;

import com.evcsms.backend.model.AdminUser;
import com.evcsms.backend.repository.AdminUserRepository;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class AdminAuthService {

    private static final long ADMIN_JWT_TTL_SECONDS = 8 * 3600;
    private static final String ADMIN_JWT_SECRET = "admin-dev-secret-change-before-production-very-long-key";

    private final AdminUserRepository adminUserRepository;

    public AdminAuthService(AdminUserRepository adminUserRepository) {
        this.adminUserRepository = adminUserRepository;
    }

    public LoginResult login(String username, String password) {
        AdminUser user = adminUserRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Invalid username or password"));

        if (!"ACTIVE".equalsIgnoreCase(user.getStatus())) {
            throw new IllegalArgumentException("User is inactive");
        }

        String incomingHash = sha256(password);
        if (!MessageDigest.isEqual(incomingHash.getBytes(StandardCharsets.UTF_8), user.getPasswordHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid username or password");
        }

        String token = generateJwt(user.getUsername(), user.getRole(), user.getFullName(), ADMIN_JWT_TTL_SECONDS);
        return new LoginResult(token, "Bearer", ADMIN_JWT_TTL_SECONDS, user.getUsername(), user.getFullName(), user.getRole());
    }

    public AuthenticatedAdmin requireAdminFromAuthorizationHeader(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing Authorization header");
        }
        String token = authorizationHeader.substring(7);
        return parseAndValidate(token);
    }

    public void resetPassword(String authorizationHeader, String currentPassword, String newPassword) {
        AuthenticatedAdmin admin = requireAdminFromAuthorizationHeader(authorizationHeader);
        AdminUser user = adminUserRepository.findByUsername(admin.username())
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found"));

        String currentHash = sha256(currentPassword);
        if (!MessageDigest.isEqual(currentHash.getBytes(StandardCharsets.UTF_8), user.getPasswordHash().getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Current password is incorrect");
        }

        user.setPasswordHash(sha256(newPassword));
        adminUserRepository.save(user);
    }

    public void requireRole(AuthenticatedAdmin admin, String... allowedRoles) {
        for (String allowedRole : allowedRoles) {
            if (allowedRole.equalsIgnoreCase(admin.role())) {
                return;
            }
        }
        throw new IllegalArgumentException("Insufficient role permissions");
    }

    private AuthenticatedAdmin parseAndValidate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid token format");
        }

        String signingInput = parts[0] + "." + parts[1];
        String expectedSignature = sign(signingInput, ADMIN_JWT_SECRET);
        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid token signature");
        }

        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String username = extractJsonValue(payloadJson, "sub");
        String role = extractJsonValue(payloadJson, "role");
        String fullName = extractJsonValue(payloadJson, "name");
        String exp = extractJsonValue(payloadJson, "exp");

        if (username == null || role == null || exp == null) {
            throw new IllegalArgumentException("Token payload invalid");
        }

        long expSeconds = Long.parseLong(exp);
        if (Instant.now().getEpochSecond() > expSeconds) {
            throw new IllegalArgumentException("Token expired");
        }

        return new AuthenticatedAdmin(username, role, fullName);
    }

    private String generateJwt(String subject, String role, String fullName, long ttlSeconds) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + ttlSeconds;

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"sub\":\"" + escapeJson(subject) + "\","
                + "\"role\":\"" + escapeJson(role) + "\"," 
                + "\"name\":\"" + escapeJson(fullName) + "\"," 
                + "\"iat\":" + issuedAt + ","
                + "\"exp\":" + expiresAt + "}";

        String encodedHeader = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;
        String signature = sign(signingInput, ADMIN_JWT_SECRET);
        return signingInput + "." + signature;
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

    private String base64UrlEncode(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte hashByte : hashBytes) {
                builder.append(String.format("%02x", hashByte));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to hash password", ex);
        }
    }

    private String extractJsonValue(String json, String key) {
        String quotedPattern = "\"" + key + "\":\"";
        int quotedStart = json.indexOf(quotedPattern);
        if (quotedStart >= 0) {
            int valueStart = quotedStart + quotedPattern.length();
            int valueEnd = json.indexOf('"', valueStart);
            if (valueEnd > valueStart) {
                return json.substring(valueStart, valueEnd);
            }
        }

        String numericPattern = "\"" + key + "\":";
        int numericStart = json.indexOf(numericPattern);
        if (numericStart >= 0) {
            int valueStart = numericStart + numericPattern.length();
            int valueEnd = valueStart;
            while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
                valueEnd++;
            }
            if (valueEnd > valueStart) {
                return json.substring(valueStart, valueEnd);
            }
        }

        return null;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record LoginResult(
            String token,
            String tokenType,
            long expiresInSeconds,
            String username,
            String fullName,
            String role
    ) {
    }

    public record AuthenticatedAdmin(String username, String role, String fullName) {
    }
}
