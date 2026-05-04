package com.evcsms.backend.service;

import com.evcsms.backend.model.PaymentRecord;
import com.evcsms.backend.repository.PaymentRepository;
import com.evcsms.backend.util.IdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;

@Service
public class PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);
    private static final String CURRENCY = "INR";
    private static final long TEMP_HOLD_EXPIRY_SECONDS = 900;

    private final PaymentRepository paymentRepository;
    private final boolean skipWebhookSignatureValidation;
    private final boolean mockWebhookSignatureValidation;
    private final String razorpayWebhookSecret;

    public PaymentService(PaymentRepository paymentRepository,
                          @Value("${app.payment.webhook.skip-signature-validation:false}") boolean skipWebhookSignatureValidation,
                          @Value("${app.payment.webhook.mock-signature-validation:true}") boolean mockWebhookSignatureValidation,
                          @Value("${razorpay.webhook-secret:}") String razorpayWebhookSecret) {
        this.paymentRepository = paymentRepository;
        this.skipWebhookSignatureValidation = skipWebhookSignatureValidation;
        this.mockWebhookSignatureValidation = mockWebhookSignatureValidation;
        this.razorpayWebhookSecret = razorpayWebhookSecret;
    }

    @Transactional
    public PreAuthResult createPreAuth(Long sessionId, BigDecimal amount) {
        String preAuthId = IdUtils.generatePrefixedId("preauth");
        MockGatewayResponse gatewayResponse = mockGatewayCall("preauth", preAuthId, amount);
        Instant now = Instant.now();

        PaymentRecord paymentRecord = PaymentRecord.builder()
                .sessionId(sessionId)
                .preAuthId(preAuthId)
                .amount(amount)
                .currency(CURRENCY)
                .operation("PREAUTH")
                .status(gatewayResponse.success() ? "SUCCESS" : "FAILED")
                .providerReference(gatewayResponse.providerReference())
                .providerPayload(gatewayResponse.rawPayload())
                .createdAt(now)
                .updatedAt(now)
                .build();
        paymentRepository.save(paymentRecord);

        return new PreAuthResult(preAuthId, true, "Temporary hold created for local-dev mock flow", amount, TEMP_HOLD_EXPIRY_SECONDS);
    }

    @Transactional
    public CaptureResult capture(String preAuthId, BigDecimal amount) {
        PaymentRecord preAuthRecord = paymentRepository.findTopByPreAuthIdAndOperationOrderByCreatedAtDesc(preAuthId, "PREAUTH")
                .orElseThrow(() -> new IllegalArgumentException("Pre-auth record not found: " + preAuthId));

        MockGatewayResponse gatewayResponse = mockGatewayCall("capture", preAuthId, amount);
        Instant now = Instant.now();

        PaymentRecord paymentRecord = PaymentRecord.builder()
                .sessionId(preAuthRecord.getSessionId())
                .preAuthId(preAuthId)
                .amount(amount)
                .currency(CURRENCY)
                .operation("CAPTURE")
                .status(gatewayResponse.success() ? "SUCCESS" : "FAILED")
                .providerReference(gatewayResponse.providerReference())
                .providerPayload(gatewayResponse.rawPayload())
                .createdAt(now)
                .updatedAt(now)
                .build();
        paymentRepository.save(paymentRecord);

        return new CaptureResult(preAuthId, amount, gatewayResponse.success());
    }

    @Transactional
    public ReleaseResult release(String preAuthId, BigDecimal amount) {
        PaymentRecord preAuthRecord = paymentRepository.findTopByPreAuthIdAndOperationOrderByCreatedAtDesc(preAuthId, "PREAUTH")
                .orElseThrow(() -> new IllegalArgumentException("Pre-auth record not found: " + preAuthId));

        MockGatewayResponse gatewayResponse = mockGatewayCall("release", preAuthId, amount);
        Instant now = Instant.now();

        PaymentRecord paymentRecord = PaymentRecord.builder()
                .sessionId(preAuthRecord.getSessionId())
                .preAuthId(preAuthId)
                .amount(amount)
                .currency(CURRENCY)
                .operation("RELEASE")
                .status(gatewayResponse.success() ? "SUCCESS" : "FAILED")
                .providerReference(gatewayResponse.providerReference())
                .providerPayload(gatewayResponse.rawPayload())
                .createdAt(now)
                .updatedAt(now)
                .build();
        paymentRepository.save(paymentRecord);

        return new ReleaseResult(preAuthId, amount, gatewayResponse.success());
    }

        @Transactional
        public RefundResult refund(String preAuthId, BigDecimal amount) {
        PaymentRecord preAuthRecord = paymentRepository.findTopByPreAuthIdAndOperationOrderByCreatedAtDesc(preAuthId, "PREAUTH")
            .orElseThrow(() -> new IllegalArgumentException("Pre-auth record not found: " + preAuthId));

        MockGatewayResponse gatewayResponse = mockGatewayCall("refund", preAuthId, amount);
        Instant now = Instant.now();

        PaymentRecord paymentRecord = PaymentRecord.builder()
            .sessionId(preAuthRecord.getSessionId())
            .preAuthId(preAuthId)
            .amount(amount)
            .currency(CURRENCY)
            .operation("REFUND")
            .status(gatewayResponse.success() ? "SUCCESS" : "FAILED")
            .providerReference(gatewayResponse.providerReference())
            .providerPayload(gatewayResponse.rawPayload())
            .createdAt(now)
            .updatedAt(now)
            .build();
        paymentRepository.save(paymentRecord);

        return new RefundResult(preAuthId, amount, gatewayResponse.success(), gatewayResponse.providerReference());
        }

    @Transactional
        public WebhookResult handleWebhook(String eventType,
                           String paymentStatus,
                           String preAuthId,
                           BigDecimal amount,
                           String payload,
                           String signature) {
        validateWebhookSignature(payload, signature);

        PaymentRecord preAuthRecord = paymentRepository.findTopByPreAuthIdAndOperationOrderByCreatedAtDesc(preAuthId, "PREAUTH")
                .orElseThrow(() -> new IllegalArgumentException("Pre-auth record not found for webhook: " + preAuthId));

        preAuthRecord.setStatus(paymentStatus.toUpperCase());
        preAuthRecord.setUpdatedAt(Instant.now());
        paymentRepository.save(preAuthRecord);

        Instant now = Instant.now();
        PaymentRecord webhookRecord = PaymentRecord.builder()
                .sessionId(preAuthRecord.getSessionId())
                .preAuthId(preAuthId)
                .amount(amount)
                .currency(CURRENCY)
                .operation("WEBHOOK_" + eventType.toUpperCase())
                .status("RECEIVED")
                .providerReference(IdUtils.generatePrefixedId("webhook"))
                .providerPayload(payload)
                .createdAt(now)
                .updatedAt(now)
                .build();
        paymentRepository.save(webhookRecord);

        if ("SUCCESS".equalsIgnoreCase(paymentStatus)) {
            capture(preAuthId, amount);
        } else if ("FAILURE".equalsIgnoreCase(paymentStatus)) {
            refund(preAuthId, amount);
        }

        // TODO Replace webhook signature verification and event parsing with Razorpay SDK webhook utilities.
        return new WebhookResult(preAuthId, eventType, "ACCEPTED");
    }

    @Transactional(readOnly = true)
    public Optional<String> findLatestPreAuthIdBySessionId(Long sessionId) {
        return paymentRepository.findTopBySessionIdAndOperationOrderByCreatedAtDesc(sessionId, "PREAUTH")
                .map(PaymentRecord::getPreAuthId);
    }

    private MockGatewayResponse mockGatewayCall(String operation, String preAuthId, BigDecimal amount) {
        String providerReference = IdUtils.generatePrefixedId("mock-" + operation);
        String payload = "{\"operation\":\"" + operation + "\",\"preAuthId\":\"" + preAuthId + "\",\"amount\":" + amount + "}";

        logger.info("Mock payment HTTP call: operation={}, preAuthId={}, amount={}", operation, preAuthId, amount);
        logger.debug("Mock provider payload: {}", payload);

        // TODO Replace this local-dev mock HTTP call flow with Razorpay SDK integration.
        return new MockGatewayResponse(true, providerReference, payload);
    }

    /**
     * Public method to validate Razorpay webhook signature.
     * Uses HMAC-SHA256 for validation.
     */
    public void validateWebhookSignature(String payload, String signature) {
        if (skipWebhookSignatureValidation) {
            logger.warn("[WEBHOOK] Skipping signature validation (test mode)");
            return;
        }

        if (signature == null || signature.isBlank()) {
            throw new SecurityException("Missing webhook signature");
        }

        if (mockWebhookSignatureValidation) {
            logger.warn("[WEBHOOK] Using mock signature validation (dev mode)");
            if (!"dev-valid-signature".equals(signature)) {
                throw new SecurityException("Invalid mock webhook signature");
            }
            return;
        }

        // Real Razorpay signature validation using HMAC-SHA256
        if (razorpayWebhookSecret == null || razorpayWebhookSecret.isBlank()) {
            throw new SecurityException("Razorpay webhook secret not configured");
        }

        try {
            String expectedSignature = generateHmacSha256(payload, razorpayWebhookSecret);
            if (!expectedSignature.equals(signature)) {
                logger.error("[WEBHOOK] Signature mismatch. Expected: {}, Got: {}", expectedSignature, signature);
                throw new SecurityException("Invalid webhook signature");
            }
            logger.debug("[WEBHOOK] Signature validation successful");
        } catch (Exception e) {
            logger.error("[WEBHOOK] Signature validation error", e);
            throw new SecurityException("Webhook signature validation failed", e);
        }
    }

    /**
     * Generate HMAC-SHA256 hash for Razorpay webhook verification.
     */
    private String generateHmacSha256(String payload, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    /**
     * Handle Razorpay webhook events (payment.authorized, payment.captured, payment.failed, refund.processed).
     */
    @Transactional
    public void handleRazorpayWebhookEvent(String eventType, String paymentId, String status, Long amountInPaise) {
        BigDecimal amount = BigDecimal.valueOf(amountInPaise).divide(BigDecimal.valueOf(100)); // Convert paise to rupees
        
        logger.info("[WEBHOOK_EVENT] Processing event: type={}, paymentId={}, status={}, amount={}", 
                   eventType, paymentId, status, amount);

        switch (eventType) {
            case "payment.authorized":
                handlePaymentAuthorized(paymentId, amount);
                break;
            case "payment.captured":
                handlePaymentCaptured(paymentId, status, amount);
                break;
            case "payment.failed":
                handlePaymentFailed(paymentId);
                break;
            case "refund.processed":
                handleRefundProcessed(paymentId, status);
                break;
            default:
                logger.warn("[WEBHOOK_EVENT] Unhandled event type: {}", eventType);
        }
    }

    @Transactional
    private void handlePaymentAuthorized(String paymentId, BigDecimal amount) {
        logger.info("[WEBHOOK] payment.authorized - PaymentId: {}, Amount: {}", paymentId, amount);
        // Payment authorized - preauth successful
        // In real flow, update payment status in DB
    }

    @Transactional
    private void handlePaymentCaptured(String paymentId, String status, BigDecimal amount) {
        logger.info("[WEBHOOK] payment.captured - PaymentId: {}, Status: {}, Amount: {}", paymentId, status, amount);
        // Payment captured - charge applied successfully
        // Update session and payment record
    }

    @Transactional
    private void handlePaymentFailed(String paymentId) {
        logger.warn("[WEBHOOK] payment.failed - PaymentId: {}", paymentId);
        // Payment failed - release preauth hold and unlock session
    }

    @Transactional
    private void handleRefundProcessed(String paymentId, String status) {
        logger.info("[WEBHOOK] refund.processed - PaymentId: {}, Status: {}", paymentId, status);
        // Refund completed - update payment record
    }

    public record PreAuthResult(
            String preAuthId,
            boolean tempHold,
            String tempHoldInfo,
            BigDecimal amount,
            long expiresInSeconds
    ) {
    }

    public record CaptureResult(String preAuthId, BigDecimal amount, boolean success) {
    }

    public record ReleaseResult(String preAuthId, BigDecimal amount, boolean success) {
    }

    public record RefundResult(String preAuthId, BigDecimal amount, boolean success, String refundId) {
    }

    public record WebhookResult(String preAuthId, String eventType, String status) {
    }

    private record MockGatewayResponse(boolean success, String providerReference, String rawPayload) {
    }
}
