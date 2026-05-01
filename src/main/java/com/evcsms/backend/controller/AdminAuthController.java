package com.evcsms.backend.controller;

import com.evcsms.backend.dto.AdminLoginRequest;
import com.evcsms.backend.dto.ResetPasswordRequest;
import com.evcsms.backend.service.AdminAuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/auth")
@CrossOrigin(origins = "*", maxAge = 3600)
public class AdminAuthController {

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public AdminAuthService.LoginResult login(@Valid @RequestBody AdminLoginRequest request) {
        try {
            return adminAuthService.login(request.username(), request.password());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    @GetMapping("/me")
    @ResponseStatus(HttpStatus.OK)
    public AdminAuthService.AuthenticatedAdmin me(@RequestHeader("Authorization") String authorization) {
        try {
            return adminAuthService.requireAdminFromAuthorizationHeader(authorization);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        }
    }

    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.OK)
    public void resetPassword(
            @RequestHeader("Authorization") String authorization,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        try {
            adminAuthService.resetPassword(authorization, request.currentPassword(), request.newPassword());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        // Client-side will remove token
    }
}
