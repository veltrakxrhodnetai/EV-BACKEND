package com.evcsms.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class OtpAuthService {

    private static final Logger logger = LoggerFactory.getLogger(OtpAuthService.class);

    private static final long JWT_TTL_SECONDS = 3600;
    private static final String JWT_SECRET = "dev-secret-change-before-production-very-long-key";

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, OtpEntry> otpStore = new ConcurrentHashMap<>();
    private final Msg91OtpService msg91OtpService;
    private final long otpTtlSeconds;
    private final boolean allowDevTestOtp;
    private final boolean smsRequired;

    public OtpAuthService(
            Msg91OtpService msg91OtpService,
            @Value("${app.auth.otp.ttl-seconds:300}") long otpTtlSeconds,
            @Value("${app.auth.otp.allow-dev-test-otp:false}") boolean allowDevTestOtp,
            @Value("${app.auth.otp.sms-required:true}") boolean smsRequired
    ) {
        this.msg91OtpService = msg91OtpService;
        this.otpTtlSeconds = otpTtlSeconds;
        this.allowDevTestOtp = allowDevTestOtp;
        this.smsRequired = smsRequired;
    }

    public long requestOtp(String mobile) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        Instant expiresAt = Instant.now().plusSeconds(otpTtlSeconds);
        otpStore.put(mobile, new OtpEntry(otp, expiresAt));

        Msg91OtpService.SmsSendResult smsSendResult = msg91OtpService.sendOtp(mobile, otp);
        if (!smsSendResult.success() && smsRequired) {
            otpStore.remove(mobile);
            throw new IllegalStateException(smsSendResult.message());
        }

        if (!smsSendResult.success()) {
            logger.warn("OTP SMS failed but sms-required=false. {} Generated OTP for mobile {} (dev fallback): {}",
                    smsSendResult.message(), mobile, otp);
        } else {
            logger.info("OTP generated and sent for mobile {}", mobile);
        }
        return otpTtlSeconds;
    }

    public JwtToken verifyOtp(String mobile, String otp) {
        // Optional dev mode to simplify local testing.
        if (allowDevTestOtp && "123456".equals(otp)) {
            logger.warn("DEV MODE: Accepting test OTP 123456 for mobile {}", mobile);
            String token = generateJwt(mobile, JWT_TTL_SECONDS);
            return new JwtToken(token, JWT_TTL_SECONDS);
        }

        OtpEntry entry = otpStore.get(mobile);
        if (entry == null) {
            throw new IllegalArgumentException("OTP not requested for this mobile number");
        }

        if (Instant.now().isAfter(entry.expiresAt())) {
            otpStore.remove(mobile);
            throw new IllegalArgumentException("OTP expired");
        }

        if (!entry.otp().equals(otp)) {
            throw new IllegalArgumentException("Invalid OTP");
        }

        otpStore.remove(mobile);
        String token = generateJwt(mobile, JWT_TTL_SECONDS);
        return new JwtToken(token, JWT_TTL_SECONDS);
    }

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

    private record OtpEntry(String otp, Instant expiresAt) {
    }

    public record JwtToken(String token, long expiresInSeconds) {
    }
}
