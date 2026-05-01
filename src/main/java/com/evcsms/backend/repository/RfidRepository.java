package com.evcsms.backend.repository;

import com.evcsms.backend.model.Rfid;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RfidRepository extends JpaRepository<Rfid, UUID> {

    Optional<Rfid> findByRfidUid(String rfidUid);
}
