package com.evcsms.backend.service;

import com.evcsms.backend.model.ChargingSession;
import com.evcsms.backend.repository.ChargingSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Handles sessions where the customer's payment was collected but charging never started
 * (charger offline, plug check failed, RemoteStart rejected/timed out, StartTransaction never arrived).
 *
 * Such sessions are parked in {@link #START_FAILED}. The customer can then either retry charging with the
 * amount already paid (pay-and-start without a new payment) or ask for a refund. If they do neither within
 * {@code app.charging.failed-start-auto-refund-seconds}, the payment is refunded automatically.
 */
@Service
public class FailedStartRefundService {

    public static final String START_FAILED = "START_FAILED";

    private static final Logger logger = LoggerFactory.getLogger(FailedStartRefundService.class);

    private final ChargingSessionRepository chargingSessionRepository;
    private final PaymentService paymentService;
    private final Msg91OtpService msg91OtpService;
    private final TransactionTemplate transactionTemplate;
    private final long pendingStartTimeoutSeconds;
    private final long autoRefundSeconds;

    public FailedStartRefundService(
            ChargingSessionRepository chargingSessionRepository,
            PaymentService paymentService,
            Msg91OtpService msg91OtpService,
            PlatformTransactionManager transactionManager,
            @Value("${app.charging.pending-start-timeout-seconds:180}") long pendingStartTimeoutSeconds,
            @Value("${app.charging.failed-start-auto-refund-seconds:300}") long autoRefundSeconds
    ) {
        this.chargingSessionRepository = chargingSessionRepository;
        this.paymentService = paymentService;
        this.msg91OtpService = msg91OtpService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.pendingStartTimeoutSeconds = Math.max(60, pendingStartTimeoutSeconds);
        this.autoRefundSeconds = Math.max(60, autoRefundSeconds);
    }

    /** True when the session holds a customer payment that has not been settled or refunded yet. */
    public static boolean hasHeldPayment(ChargingSession session) {
        return session.getPreauthId() != null
                && "PREAUTH_SUCCESS".equalsIgnoreCase(session.getPaymentStatus())
                && !"OWNER".equalsIgnoreCase(session.getStartedBy());
    }

    public long getPendingStartTimeoutSeconds() {
        return pendingStartTimeoutSeconds;
    }

    /** Seconds left before a START_FAILED session is refunded automatically, or null when not applicable. */
    public Long secondsUntilAutoRefund(ChargingSession session) {
        if (!START_FAILED.equalsIgnoreCase(session.getStatus()) || session.getEndedAt() == null) {
            return null;
        }
        long elapsed = Duration.between(session.getEndedAt(), LocalDateTime.now()).getSeconds();
        return Math.max(0, autoRefundSeconds - elapsed);
    }

    /**
     * Marks a session whose charging could not be started. Paid sessions go to START_FAILED so the customer
     * can choose retry or refund; unpaid sessions simply fail.
     */
    public void markStartFailed(ChargingSession session, String reason) {
        LocalDateTime now = LocalDateTime.now();
        if (hasHeldPayment(session)) {
            session.setStatus(START_FAILED);
            session.setEndedAt(now);
            chargingSessionRepository.save(session);
            logger.warn("Session {} could not start after payment {} (reason: {}). Awaiting customer retry/refund choice; auto-refund in {}s",
                    session.getId(), session.getPreauthId(), reason, autoRefundSeconds);
        } else {
            session.setStatus("FAILED");
            session.setEndedAt(now);
            chargingSessionRepository.save(session);
            logger.warn("Session {} failed to start without a held payment (reason: {})", session.getId(), reason);
        }
    }

    /**
     * Fails a PENDING_START session whose charger never sent StartTransaction. Re-checked under a row lock so a
     * StartTransaction that arrives at the same moment wins.
     */
    public void markPendingStartFailed(Long sessionId, String reason) {
        transactionTemplate.executeWithoutResult(status -> chargingSessionRepository.findByIdForUpdate(sessionId)
                .filter(session -> "PENDING_START".equalsIgnoreCase(session.getStatus()) && session.getOcppTransactionId() == null)
                .ifPresent(session -> markStartFailed(session, reason)));
    }

    /**
     * Refunds the full amount of a paid session that never started charging, then notifies the customer by SMS.
     * Safe to call concurrently (customer click and auto-refund job): the row lock guarantees a single refund.
     */
    public RefundOutcome refundFailedStart(Long sessionId, String trigger) {
        RefundOutcome outcome = transactionTemplate.execute(status -> {
            ChargingSession session = chargingSessionRepository.findByIdForUpdate(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

            if (!START_FAILED.equalsIgnoreCase(session.getStatus())) {
                throw new IllegalStateException("Session is not awaiting a refund decision. Current status: " + session.getStatus());
            }
            if (!hasHeldPayment(session)) {
                throw new IllegalStateException("No held payment found for session " + sessionId);
            }

            double amount = session.getPreauthAmount() == null ? 0.0 : session.getPreauthAmount();
            PaymentService.RefundResult refundResult = paymentService.refund(session.getPreauthId(), BigDecimal.valueOf(amount));

            session.setStatus("FAILED");
            session.setTotalAmount(0.0);
            session.setBaseAmount(0.0);
            session.setGstAmount(0.0);
            if (session.getEndedAt() == null) {
                session.setEndedAt(LocalDateTime.now());
            }

            if (refundResult.success()) {
                session.setPaymentStatus("REFUNDED");
                session.setRefundAmount(amount);
                session.setRefundId(refundResult.refundId());
                logger.info("Refunded ₹{} for session {} that never started charging (trigger={}, refundId={})",
                        amount, sessionId, trigger, refundResult.refundId());
            } else {
                // Left for manual follow-up from the admin portal; retrying automatically could double-refund
                // if the gateway actually processed the request.
                session.setPaymentStatus("REFUND_FAILED");
                logger.error("Refund FAILED for session {} payment {} amount ₹{} (trigger={}); manual action required",
                        sessionId, session.getPreauthId(), amount, trigger);
            }
            chargingSessionRepository.save(session);

            return new RefundOutcome(sessionId, amount, refundResult.success(), session.getPhoneNumber(), session.getPaymentStatus());
        });

        if (outcome != null && outcome.success()) {
            msg91OtpService.sendRefundInitiatedMessage(outcome.phoneNumber(), outcome.amount());
        }
        return outcome;
    }

    /**
     * Records a Razorpay payment reported by webhook for a session whose pay-and-start call never reached the
     * backend (app closed, network drop). The scheduler then moves it to START_FAILED so it is refunded or retried.
     */
    public void linkWebhookPayment(Long sessionId, String razorpayPaymentId, BigDecimal amount) {
        transactionTemplate.executeWithoutResult(status -> chargingSessionRepository.findByIdForUpdate(sessionId).ifPresent(session -> {
            if (razorpayPaymentId.equals(session.getPreauthId())) {
                return;
            }
            if (!"PENDING_PAYMENT".equalsIgnoreCase(session.getStatus()) || session.getPreauthId() != null) {
                logger.warn("Webhook payment {} for session {} not linked (status={}, existing preauthId={})",
                        razorpayPaymentId, sessionId, session.getStatus(), session.getPreauthId());
                return;
            }
            paymentService.recordRealPaymentId(sessionId, razorpayPaymentId, amount);
            session.setPreauthId(razorpayPaymentId);
            session.setPaymentStatus("PREAUTH_SUCCESS");
            chargingSessionRepository.save(session);
            logger.info("Linked webhook payment {} to session {} awaiting start", razorpayPaymentId, sessionId);
        }));
    }

    @Scheduled(fixedDelayString = "${app.charging.failed-start-check-interval-millis:30000}")
    public void reconcileFailedStarts() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime pendingCutoff = now.minusSeconds(pendingStartTimeoutSeconds);

        // 1. RemoteStart accepted but the charger never sent StartTransaction.
        for (ChargingSession session : chargingSessionRepository
                .findByStatusAndOcppTransactionIdIsNullAndUpdatedAtBefore("PENDING_START", pendingCutoff)) {
            runSafely(session.getId(), "pending-start timeout", () -> markPendingStartFailed(session.getId(),
                    "charger did not start the transaction within " + pendingStartTimeoutSeconds + "s"));
        }

        // 2. Payment collected but the start request never completed (client dropped mid-flow).
        for (ChargingSession session : chargingSessionRepository
                .findByStatusAndPaymentStatusAndUpdatedAtBefore("PENDING_PAYMENT", "PREAUTH_SUCCESS", pendingCutoff)) {
            runSafely(session.getId(), "stuck paid session", () -> transactionTemplate.executeWithoutResult(status ->
                    chargingSessionRepository.findByIdForUpdate(session.getId())
                            .filter(locked -> "PENDING_PAYMENT".equalsIgnoreCase(locked.getStatus()) && hasHeldPayment(locked))
                            .ifPresent(locked -> markStartFailed(locked, "payment received but charging was never started"))));
        }

        // 3. Customer did not choose retry or refund in time.
        List<ChargingSession> expired = chargingSessionRepository
                .findByStatusAndEndedAtBefore(START_FAILED, now.minusSeconds(autoRefundSeconds));
        for (ChargingSession session : expired) {
            runSafely(session.getId(), "auto-refund", () -> refundFailedStart(session.getId(), "AUTO_TIMEOUT"));
        }
    }

    private void runSafely(Long sessionId, String action, Runnable task) {
        try {
            task.run();
        } catch (IllegalStateException ex) {
            logger.debug("Skipped {} for session {}: {}", action, sessionId, ex.getMessage());
        } catch (Exception ex) {
            logger.error("Failed {} for session {}: {}", action, sessionId, ex.getMessage(), ex);
        }
    }

    public record RefundOutcome(Long sessionId, double amount, boolean success, String phoneNumber, String paymentStatus) {
    }
}
