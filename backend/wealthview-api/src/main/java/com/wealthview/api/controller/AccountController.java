package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.account.AccountService;
import com.wealthview.core.account.dto.AccountRequest;
import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.common.PageResponse;
import com.wealthview.core.portfolio.TheoreticalPortfolioService;
import com.wealthview.core.portfolio.dto.PortfolioHistoryResponse;
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
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;
    private final TheoreticalPortfolioService theoreticalPortfolioService;

    public AccountController(AccountService accountService,
                             TheoreticalPortfolioService theoreticalPortfolioService) {
        this.accountService = accountService;
        this.theoreticalPortfolioService = theoreticalPortfolioService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody AccountRequest request) {
        var response = accountService.create(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<PageResponse<AccountResponse>> list(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        var response = accountService.list(principal.tenantId(), PageRequest.of(page, size));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<AccountResponse> get(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        var response = accountService.get(principal.tenantId(), id);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<AccountResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody AccountRequest request) {
        var response = accountService.update(principal.tenantId(), id, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        accountService.delete(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/theoretical-history")
    public ResponseEntity<PortfolioHistoryResponse> getTheoreticalHistory(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "2") int years) {
        var response = theoreticalPortfolioService.computeHistory(principal.tenantId(), id, years);
        return ResponseEntity.ok(response);
    }
}
