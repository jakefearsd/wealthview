package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.income.IncomeSourceService;
import com.wealthview.core.income.dto.CreateIncomeSourceRequest;
import com.wealthview.core.income.dto.IncomeSourceResponse;
import com.wealthview.core.income.dto.UpdateIncomeSourceRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/income-sources")
public class IncomeSourceController {

    private static final Logger log = LoggerFactory.getLogger(IncomeSourceController.class);

    private final IncomeSourceService incomeSourceService;

    public IncomeSourceController(IncomeSourceService incomeSourceService) {
        this.incomeSourceService = incomeSourceService;
    }

    @PostMapping
    public ResponseEntity<IncomeSourceResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody CreateIncomeSourceRequest request) {
        log.info("Creating income source '{}' for tenant {}", request.name(), principal.tenantId());
        var result = incomeSourceService.create(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<IncomeSourceResponse>> list(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(incomeSourceService.list(principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<IncomeSourceResponse> get(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(incomeSourceService.get(principal.tenantId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<IncomeSourceResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateIncomeSourceRequest request) {
        return ResponseEntity.ok(incomeSourceService.update(principal.tenantId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        incomeSourceService.delete(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
