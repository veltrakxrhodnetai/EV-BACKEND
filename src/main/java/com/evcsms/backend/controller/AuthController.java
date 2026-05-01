package com.evcsms.backend.controller;

import com.evcsms.backend.dto.RequestOtpRequest;
import com.evcsms.backend.dto.RequestOtpResponse;
import com.evcsms.backend.dto.VerifyOtpRequest;
import com.evcsms.backend.dto.VerifyOtpResponse;
import com.evcsms.backend.service.OtpAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final OtpAuthService otpAuthService;

    public AuthController(OtpAuthService otpAuthService) {
        this.otpAuthService = otpAuthService;
    }

    @PostMapping("/request-otp")
    @ResponseStatus(HttpStatus.OK)
    public RequestOtpResponse requestOtp(@Valid @RequestBody RequestOtpRequest request) {
        try {
            long expiresInSeconds = otpAuthService.requestOtp(request.mobile());
            return new RequestOtpResponse("OTP sent successfully", expiresInSeconds);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage(), ex);
        }
    }

    @PostMapping("/verify-otp")
    @ResponseStatus(HttpStatus.OK)
    public VerifyOtpResponse verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        try {
            OtpAuthService.JwtToken jwtToken = otpAuthService.verifyOtp(request.mobile(), request.otp());
            return new VerifyOtpResponse(jwtToken.token());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }
}
