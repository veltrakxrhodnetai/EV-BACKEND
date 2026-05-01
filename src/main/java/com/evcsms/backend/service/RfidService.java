package com.evcsms.backend.service;

import com.evcsms.backend.model.Rfid;
import com.evcsms.backend.model.RfidStatus;
import com.evcsms.backend.repository.RfidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class RfidService {

    private static final Logger logger = LoggerFactory.getLogger(RfidService.class);

    private final RfidRepository rfidRepository;

    public RfidService(RfidRepository rfidRepository) {
        this.rfidRepository = rfidRepository;
    }

    @Transactional
    public Rfid issueRfid(UUID userId, String rfidUid) {
        rfidRepository.findByRfidUid(rfidUid).ifPresent(existing -> {
            throw new IllegalArgumentException("RFID UID already exists: " + rfidUid);
        });

        Rfid rfid = Rfid.builder()
                .userId(userId)
                .rfidUid(rfidUid)
                .status(RfidStatus.ACTIVE)
                .issuedAt(Instant.now())
                .blockedAt(null)
                .build();

        Rfid saved = rfidRepository.save(rfid);
        logger.info("Issued RFID uid={} for userId={}", rfidUid, userId);

        // Sample DB interaction: INSERT into rfids(...) through JPA save above.
        // TODO Link issued RFID to wallet/account balance ledger for auto-debit charging sessions.
        return saved;
    }

    @Transactional
    public void blockRfid(String rfidUid) {
        Rfid rfid = rfidRepository.findByRfidUid(rfidUid)
                .orElseThrow(() -> new IllegalArgumentException("RFID not found: " + rfidUid));

        rfid.setStatus(RfidStatus.BLOCKED);
        rfid.setBlockedAt(Instant.now());
        rfidRepository.save(rfid);

        logger.info("Blocked RFID uid={}", rfidUid);
        // Sample DB interaction: UPDATE rfids set status='BLOCKED', blocked_at=... where rfid_uid=?
        // TODO On block, suspend wallet transactions and invalidate active charging authorization tokens.
    }

    @Transactional(readOnly = true)
    public RfidStatus validateRfid(String rfidUid) {
        return rfidRepository.findByRfidUid(rfidUid)
                .map(rfid -> {
                    // Sample DB interaction: SELECT * from rfids where rfid_uid=?
                    // TODO At validation time, also check linked wallet status and minimum balance.
                    return rfid.getStatus();
                })
                .orElse(RfidStatus.NOT_FOUND);
    }
}
