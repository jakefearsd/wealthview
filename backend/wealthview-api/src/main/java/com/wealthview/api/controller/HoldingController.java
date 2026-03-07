package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.holding.HoldingService;
import com.wealthview.core.holding.dto.HoldingRequest;
import com.wealthview.core.holding.dto.HoldingResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class HoldingController {

    private final HoldingService holdingService;

    public HoldingController(HoldingService holdingService) {
        this.holdingService = holdingService;
    }

    @GetMapping("/accounts/{accountId}/holdings")
    public ResponseEntity<List<HoldingResponse>> listByAccount(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID accountId) {
        var holdings = holdingService.listByAccount(principal.tenantId(), accountId);
        return ResponseEntity.ok(holdings);
    }

    @GetMapping("/holdings/{id}")
    public ResponseEntity<HoldingResponse> getById(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        var response = holdingService.getById(principal.tenantId(), id);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/holdings")
    public ResponseEntity<HoldingResponse> createManual(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody HoldingRequest request) {
        var response = holdingService.createManual(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/holdings/{id}")
    public ResponseEntity<HoldingResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody HoldingRequest request) {
        var response = holdingService.update(principal.tenantId(), id, request);
        return ResponseEntity.ok(response);
    }
}
