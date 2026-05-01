package com.evcsms.backend.controller;

import com.evcsms.backend.dto.MockCaptureRequest;
import com.evcsms.backend.dto.MockGatewayResponse;
import com.evcsms.backend.dto.MockPreAuthRequest;
import com.evcsms.backend.dto.MockReleaseRequest;
import com.evcsms.backend.service.MockPaymentGatewayService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/mock-gateway")
public class MockPaymentGatewayController {

    private final MockPaymentGatewayService mockPaymentGatewayService;

    public MockPaymentGatewayController(MockPaymentGatewayService mockPaymentGatewayService) {
        this.mockPaymentGatewayService = mockPaymentGatewayService;
    }

    /**
     * Sample response:
     * {
     *   "preAuthId": "preauth-a4f9f6af-cc9a-4a7d-8cba-8f5b0cf2f7a5",
     *   "status": "AUTHORIZED",
     *   "providerReference": "mock-preauth-9f3d2f14-2e18-41f4-bca1-c60793f96ee3",
     *   "amount": 250.00,
     *   "currency": "INR",
     *   "mock": true,
     *   "timestamp": "2026-03-02T10:15:30Z"
     * }
     */
    @PostMapping("/preauth")
    @ResponseStatus(HttpStatus.OK)
    public MockGatewayResponse preAuth(@Valid @RequestBody MockPreAuthRequest request) {
        try {
            return mockPaymentGatewayService.preAuth(request.sessionId(), request.amount(), request.currency());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Sample response:
     * {
     *   "preAuthId": "preauth-a4f9f6af-cc9a-4a7d-8cba-8f5b0cf2f7a5",
     *   "status": "CAPTURED",
     *   "providerReference": "mock-capture-b457410f-a2e2-4b92-af0d-72217b88b5f2",
     *   "amount": 250.00,
     *   "currency": "INR",
     *   "mock": true,
     *   "timestamp": "2026-03-02T10:16:10Z"
     * }
     */
    @PostMapping("/capture")
    @ResponseStatus(HttpStatus.OK)
    public MockGatewayResponse capture(@Valid @RequestBody MockCaptureRequest request) {
        try {
            return mockPaymentGatewayService.capture(request.preAuthId(), request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }

    /**
     * Sample response:
     * {
     *   "preAuthId": "preauth-a4f9f6af-cc9a-4a7d-8cba-8f5b0cf2f7a5",
     *   "status": "RELEASED",
     *   "providerReference": "mock-release-0ce82bc4-0054-44db-86de-1db63f7f6f2f",
     *   "amount": 250.00,
     *   "currency": "INR",
     *   "mock": true,
     *   "timestamp": "2026-03-02T10:18:55Z"
     * }
     */
    @PostMapping("/release")
    @ResponseStatus(HttpStatus.OK)
    public MockGatewayResponse release(@Valid @RequestBody MockReleaseRequest request) {
        try {
            return mockPaymentGatewayService.release(request.preAuthId(), request.amount());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
    }
}
