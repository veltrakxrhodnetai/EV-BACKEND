package com.evcsms.backend.controller;

import com.evcsms.backend.model.Settlement;
import com.evcsms.backend.service.SettlementService;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/owner/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @PostMapping("/mark")
    public ResponseEntity<?> markSettlement(@RequestBody MarkSettlementRequest request) {
        try {
            Settlement settlement = settlementService.markSettlement(request.ownerId(), request.amount());
            return ResponseEntity.ok(toResponse(settlement));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    @GetMapping("/{ownerId}")
    public ResponseEntity<?> getSettlementStatus(@PathVariable Long ownerId) {
        try {
            Settlement settlement = settlementService.getSettlementStatus(ownerId);
            return ResponseEntity.ok(toResponse(settlement));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private Map<String, Object> toResponse(Settlement settlement) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", settlement.getId());
        response.put("ownerId", settlement.getOwnerId());
        response.put("totalRevenue", settlement.getTotalRevenue());
        response.put("settledAmount", settlement.getSettledAmount());
        response.put("pendingAmount", settlement.getPendingAmount());
        response.put("status", settlement.getStatus().name());
        return response;
    }

    public record MarkSettlementRequest(
            @NotNull Long ownerId,
            @NotNull @Positive Double amount
    ) {
    }
}
