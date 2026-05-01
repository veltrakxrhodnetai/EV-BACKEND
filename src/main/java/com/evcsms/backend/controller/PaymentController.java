package com.evcsms.backend.controller;

import com.evcsms.backend.audit.Audit;
import com.evcsms.backend.dto.PaymentCaptureRequest;
import com.evcsms.backend.dto.PaymentPreAuthRequest;
import com.evcsms.backend.dto.PaymentRefundRequest;
import com.evcsms.backend.service.PaymentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;
    private final String razorpayKeyId;

    public PaymentController(PaymentService paymentService,
                             @Value("${razorpay.key-id:}") String razorpayKeyId) {
        this.paymentService = paymentService;
        this.objectMapper = new ObjectMapper();
        this.razorpayKeyId = razorpayKeyId;
    }

    @GetMapping("/config")
    public ResponseEntity<?> getPaymentConfig() {
        return ResponseEntity.ok(java.util.Map.of(
                "provider", "RAZORPAY",
                "enabled", razorpayKeyId != null && !razorpayKeyId.isBlank(),
                "keyId", razorpayKeyId == null ? "" : razorpayKeyId
        ));
    }

    /**
     * Creates a temporary pre-authorization hold for the given session and amount.
     *
     * Safety checks:
     * - Validate authenticated caller is allowed to act on the target session.
     * - Validate amount bounds/currency and ensure session is in a payable state.
     *
     * Idempotency:
     * - For production use an idempotency key (e.g., X-Idempotency-Key) per request and persist results
     *   so retries do not create duplicate holds.
     */
    @PostMapping("/preauth")
    @ResponseStatus(HttpStatus.OK)
    @Audit
    public PaymentService.PreAuthResult preAuth(@Valid @RequestBody PaymentPreAuthRequest request) {
        try {
            return paymentService.createPreAuth(request.sessionId(), request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Captures an existing pre-authorization hold.
     *
     * Safety checks:
     * - Ensure preAuthId exists and belongs to the expected tenant/session scope.
     * - Ensure capture amount does not exceed approved hold limits.
     *
     * Idempotency:
     * - Repeat capture requests should be deduplicated by an idempotency key and current payment state.
     */
    @PostMapping("/capture")
    @ResponseStatus(HttpStatus.OK)
    @Audit
    public PaymentService.CaptureResult capture(@Valid @RequestBody PaymentCaptureRequest request) {
        try {
            return paymentService.capture(request.preAuthId(), request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Refunds a previously authorized/captured payment.
     *
     * Safety checks:
     * - Validate refund eligibility by payment status and refundable balance.
    * - Restrict refunds to authorized owner/system workflows.
     *
     * Idempotency:
     * - Use a refund idempotency key and persist operation state to prevent duplicate refunds.
     */
    @PostMapping("/refund")
    @ResponseStatus(HttpStatus.OK)
    @Audit
    public PaymentService.RefundResult refund(@Valid @RequestBody PaymentRefundRequest request) {
        try {
            return paymentService.refund(request.preAuthId(), request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Razorpay webhook handler for payment events.
     * Expected header: X-Razorpay-Signature
     * Events handled:
     * - payment.authorized: Pre-authorization successful
     * - payment.captured: Payment captured (charge applied)
     * - payment.failed: Payment failed (release hold)
     * - refund.processed: Refund completed
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> handleRazorpayWebhook(
            @RequestBody String payload,
            @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature) {
        try {
            // Validate signature (will throw if invalid)
            paymentService.validateWebhookSignature(payload, signature);

            // Parse webhook payload
            JsonNode event = objectMapper.readTree(payload);
            String eventType = event.get("event").asText();
            JsonNode eventData = event.get("payload").get("payment").get("entity");

            String paymentId = eventData.get("id").asText();
            String status = eventData.get("status").asText();
            Long amount = eventData.get("amount").asLong(); // in lowest denomination (paise for INR)

            logger.info("[WEBHOOK] Received {} event for payment {}", eventType, paymentId);

            // Route to service for processing
            paymentService.handleRazorpayWebhookEvent(eventType, paymentId, status, amount);

            logger.info("[WEBHOOK] Successfully processed {} event", eventType);
            return ResponseEntity.ok("{\"status\":\"received\"}");

        } catch (SecurityException e) {
            logger.error("[WEBHOOK] Signature validation failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            logger.error("[WEBHOOK] Error processing webhook", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
}
