package com.evcsms.backend.repository;

import com.evcsms.backend.model.OwnerAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OwnerAccountRepository extends JpaRepository<OwnerAccount, Long> {
    /**
     * Find owner by mobile number for authentication
     */
    Optional<OwnerAccount> findByMobileNumber(String mobileNumber);
}
