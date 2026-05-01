package com.evcsms.backend.controller;

import com.evcsms.backend.dto.*;
import com.evcsms.backend.service.CustomerAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/customer/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
public class CustomerAuthController {

    private final CustomerAuthService customerAuthService;

    public CustomerAuthController(CustomerAuthService customerAuthService) {
        this.customerAuthService = customerAuthService;
    }

    /**
     * STEP 1: Check if phone exists and whether user has a passcode
     */
    @PostMapping("/check-phone")
    @ResponseStatus(HttpStatus.OK)
    public CheckPhoneResponse checkPhone(@Valid @RequestBody CheckPhoneRequest request) {
        return customerAuthService.checkPhone(request.phoneNumber());
    }

    /**
     * Send OTP for LOGIN, REGISTER, or RESET_PASSCODE
     */
    @PostMapping("/send-otp")
    public ResponseEntity<?> sendOtp(@Valid @RequestBody SendOtpRequest request) {
        try {
            return ResponseEntity.ok(customerAuthService.sendOtp(request.phoneNumber(), request.purpose()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(java.util.Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Verify OTP and get token for next step
     */
    @PostMapping("/verify-otp")
    @ResponseStatus(HttpStatus.OK)
    public VerifyOtpResponse verifyOtp(@Valid @RequestBody VerifyOtpCustomerRequest request) {
        try {
            return customerAuthService.verifyOtp(request.phoneNumber(), request.otp(), request.purpose());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    /**
     * Login with passcode (existing user with passcode)
     */
    @PostMapping("/login-passcode")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse loginPasscode(@Valid @RequestBody LoginPasscodeRequest request) {
        try {
            return customerAuthService.loginPasscode(request.phoneNumber(), request.passcode());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    /**
     * Login with OTP (existing user without passcode)
     */
    @PostMapping("/login-otp")
    @ResponseStatus(HttpStatus.OK)
    public AuthResponse loginOtp(@Valid @RequestBody LoginOtpRequest request) {
        try {
            return customerAuthService.loginOtp(request.phoneNumber(), request.otpToken());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    /**
     * Register new customer
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        try {
            return customerAuthService.register(request.phoneNumber(), request.name(), request.otpToken());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Set or update passcode
     */
    @PostMapping("/set-passcode")
    @ResponseStatus(HttpStatus.OK)
    public void setPasscode(@Valid @RequestBody SetPasscodeRequest request) {
        try {
            customerAuthService.setPasscode(request.phoneNumber(), request.passcode(), request.otpToken());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
