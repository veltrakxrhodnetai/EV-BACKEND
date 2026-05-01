package com.evcsms.backend.repository;

import com.evcsms.backend.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    
    /**
     * Find customer by phone number
     */
    Optional<Customer> findByPhoneNumber(String phoneNumber);
    
    /**
     * Check if customer exists by phone number
     */
    boolean existsByPhoneNumber(String phoneNumber);
}
