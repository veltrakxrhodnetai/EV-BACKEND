package com.evcsms.backend.repository;

import com.evcsms.backend.model.PaymentRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentRecord, UUID> {

    Optional<PaymentRecord> findTopBySessionIdAndOperationOrderByCreatedAtDesc(Long sessionId, String operation);

    Optional<PaymentRecord> findTopByPreAuthIdAndOperationOrderByCreatedAtDesc(String preAuthId, String operation);
}