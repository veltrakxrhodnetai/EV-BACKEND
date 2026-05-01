package com.evcsms.backend.service;

import com.evcsms.backend.dto.*;
import com.evcsms.backend.model.Customer;
import com.evcsms.backend.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class CustomerAuthService {

    private static final Logger logger = LoggerFactory.getLogger(CustomerAuthService.class);
    private static final long JWT_TTL_SECONDS = 3600;
    private static final String JWT_SECRET = "dev-secret-change-before-production-very-long-key";

    // In-memory OTP token store (OTP tokens are temporary, no need for DB persistence)
    private final Map<String, OtpTokenEntry> otpTokenStore = new ConcurrentHashMap<>();
    
    private final OtpAuthService otpAuthService;
    private final CustomerRepository customerRepository;

    public CustomerAuthService(OtpAuthService otpAuthService, CustomerRepository customerRepository) {
        this.otpAuthService = otpAuthService;
        this.customerRepository = customerRepository;
    }

    /**
     * STEP 1: Check if phone exists and whether user has a passcode
     */
    @Transactional(readOnly = true)
    public CheckPhoneResponse checkPhone(String phoneNumber) {
        return customerRepository.findByPhoneNumber(phoneNumber)
                .map(customer -> new CheckPhoneResponse(true, customer.isHasPasscode()))
                .orElse(new CheckPhoneResponse(false, false));
    }

    /**
     * Send OTP for various purposes: LOGIN, REGISTER, RESET_PASSCODE
     */
    public SendOtpResponse sendOtp(String phoneNumber, SendOtpRequest.OtpPurpose purpose) {
        // For RESET_PASSCODE, user must exist and be verified
        if (purpose == SendOtpRequest.OtpPurpose.RESET_PASSCODE) {
            if (!customerRepository.existsByPhoneNumber(phoneNumber)) {
                throw new IllegalArgumentException("Phone number not found");
            }
        }

        // Use OtpAuthService to generate OTP
        long expiresInSeconds = otpAuthService.requestOtp(phoneNumber);
        logger.info("OTP requested for {} with purpose {}", phoneNumber, purpose);

        return new SendOtpResponse(
                "OTP sent successfully to +" + phoneNumber,
                expiresInSeconds
        );
    }

    /**
     * Verify OTP and return token for use in subsequent calls
     */
    public VerifyOtpResponse verifyOtp(
            String phoneNumber,
            String otp,
            SendOtpRequest.OtpPurpose purpose
    ) {
        try {
            // Verify using OtpAuthService
            OtpAuthService.JwtToken jwtToken = otpAuthService.verifyOtp(phoneNumber, otp);

            // Store the OTP token with purpose for later use
            otpTokenStore.put(
                    jwtToken.token(),
                    new OtpTokenEntry(phoneNumber, purpose, Instant.now().plusSeconds(jwtToken.expiresInSeconds()))
            );

            logger.info("OTP verified for {} with purpose {}", phoneNumber, purpose);

            return new VerifyOtpResponse(jwtToken.token());
        } catch (IllegalArgumentException ex) {
            throw ex;
        }
    }

    /**
     * Login with passcode (existing user with passcode)
     */
    @Transactional(readOnly = true)
    public AuthResponse loginPasscode(String phoneNumber, String passcode) {
        Customer customer = customerRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new IllegalArgumentException("Phone number not found"));

        if (!customer.isHasPasscode()) {
            throw new IllegalArgumentException("User does not have a passcode");
        }

        String passcodeHash = hashPasscode(passcode);
        if (!passcodeHash.equals(customer.getPasscodeHash())) {
            throw new IllegalArgumentException("Invalid passcode");
        }

        String token = generateJwt(phoneNumber, JWT_TTL_SECONDS);
        logger.info("Customer logged in with passcode: {}", phoneNumber);

        return new AuthResponse(
                token,
                "Bearer",
                JWT_TTL_SECONDS,
                true,
                customer.getName()
        );
    }

    /**
     * Login with OTP (existing user without passcode)
     */
    @Transactional(readOnly = true)
    public AuthResponse loginOtp(String phoneNumber, String otpToken) {
        OtpTokenEntry entry = otpTokenStore.get(otpToken);
        if (entry == null || !entry.phoneNumber().equals(phoneNumber)) {
            throw new IllegalArgumentException("Invalid OTP token");
        }

        if (!entry.purpose().equals(SendOtpRequest.OtpPurpose.LOGIN)) {
            throw new IllegalArgumentException("OTP token not valid for LOGIN");
        }

        if (Instant.now().isAfter(entry.expiresAt())) {
            otpTokenStore.remove(otpToken);
            throw new IllegalArgumentException("OTP token expired");
        }

        Customer customer = customerRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new IllegalArgumentException("Phone number not found"));

        String token = generateJwt(phoneNumber, JWT_TTL_SECONDS);
        logger.info("Customer logged in with OTP: {}", phoneNumber);

        return new AuthResponse(
                token,
                "Bearer",
                JWT_TTL_SECONDS,
                customer.isHasPasscode(),
                customer.getName()
        );
    }

    /**
     * Register new customer
     */
    public AuthResponse register(String phoneNumber, String name, String otpToken) {
        OtpTokenEntry entry = otpTokenStore.get(otpToken);
        if (entry == null || !entry.phoneNumber().equals(phoneNumber)) {
            throw new IllegalArgumentException("Invalid OTP token");
        }

        if (!entry.purpose().equals(SendOtpRequest.OtpPurpose.REGISTER)) {
            throw new IllegalArgumentException("OTP token not valid for REGISTER");
        }

        if (Instant.now().isAfter(entry.expiresAt())) {
            otpTokenStore.remove(otpToken);
            throw new IllegalArgumentException("OTP token expired");
        }

        if (customerRepository.existsByPhoneNumber(phoneNumber)) {
            throw new IllegalArgumentException("Phone number already registered");
        }

        Customer customer = new Customer(phoneNumber, name);
        customer = customerRepository.save(customer);

        String token = generateJwt(phoneNumber, JWT_TTL_SECONDS);
        logger.info("New customer registered: {} - {}", phoneNumber, name);

        return new AuthResponse(
                token,
                "Bearer",
                JWT_TTL_SECONDS,
                false,  // New user, no passcode yet
                customer.getName()
        );
    }

    /**
     * Set or update passcode
     * If otpToken is provided, this is a passcode reset; otherwise it's setting a new passcode after registration
     */
    public void setPasscode(String phoneNumber, String passcode, String otpToken) {
        Customer customer = customerRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new IllegalArgumentException("Phone number not found"));

        // If otpToken is provided, this is a reset - verify the token
        if (otpToken != null && !otpToken.isEmpty()) {
            OtpTokenEntry entry = otpTokenStore.get(otpToken);
            if (entry == null || !entry.phoneNumber().equals(phoneNumber)) {
                throw new IllegalArgumentException("Invalid OTP token");
            }

            if (!entry.purpose().equals(SendOtpRequest.OtpPurpose.RESET_PASSCODE)) {
                throw new IllegalArgumentException("OTP token not valid for RESET_PASSCODE");
            }

            if (Instant.now().isAfter(entry.expiresAt())) {
                otpTokenStore.remove(otpToken);
                throw new IllegalArgumentException("OTP token expired");
            }

            otpTokenStore.remove(otpToken);
            logger.info("Passcode reset for customer: {}", phoneNumber);
        }

        String passcodeHash = hashPasscode(passcode);
        customer.setPasscodeHash(passcodeHash);
        customer.setHasPasscode(true);
        customerRepository.save(customer);

        logger.info("Passcode set for customer: {}", phoneNumber);
    }

    /**
     * Hash passcode using SHA-256
     */
    private String hashPasscode(String passcode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(passcode.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }

    /**
     * Generate JWT token for authenticated user
     */
    private String generateJwt(String subject, long ttlSeconds) {
        long issuedAt = Instant.now().getEpochSecond();
        long expiresAt = issuedAt + ttlSeconds;

        String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payloadJson = "{\"sub\":\"" + subject + "\",\"iat\":" + issuedAt + ",\"exp\":" + expiresAt + "}";

        String encodedHeader = base64UrlEncode(headerJson.getBytes(StandardCharsets.UTF_8));
        String encodedPayload = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
        String signingInput = encodedHeader + "." + encodedPayload;

        String signature = sign(signingInput, JWT_SECRET);
        return signingInput + "." + signature;
    }

    /**
     * Sign JWT
     */
    private String sign(String data, String secret) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(keySpec);
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return base64UrlEncode(signatureBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign JWT", ex);
        }
    }

    /**
     * Encode bytes to Base64 URL-safe format
     */
    private String base64UrlEncode(byte[] input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(input);
    }

    /**
     * Internal record for storing OTP tokens
     */
    private record OtpTokenEntry(
            String phoneNumber,
            SendOtpRequest.OtpPurpose purpose,
            Instant expiresAt
    ) {
    }
}
