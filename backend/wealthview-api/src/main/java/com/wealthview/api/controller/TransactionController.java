package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.common.PageResponse;
import com.wealthview.core.transaction.TransactionService;
import com.wealthview.core.transaction.dto.TransactionRequest;
import com.wealthview.core.transaction.dto.TransactionResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID accountId,
            @Valid @RequestBody TransactionRequest request) {
        var response = transactionService.create(principal.tenantId(), accountId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<PageResponse<TransactionResponse>> listByAccount(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID accountId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String symbol) {
        var pageable = PageRequest.of(page, size);
        var response = symbol != null
                ? transactionService.listByAccountAndSymbol(principal.tenantId(), accountId, symbol, pageable)
                : transactionService.listByAccount(principal.tenantId(), accountId, pageable);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/transactions/{id}")
    public ResponseEntity<TransactionResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody TransactionRequest request) {
        var response = transactionService.update(principal.tenantId(), id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/transactions/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        transactionService.delete(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
