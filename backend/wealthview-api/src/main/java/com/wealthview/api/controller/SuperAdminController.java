package com.wealthview.api.controller;

import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.dto.SetActiveRequest;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.core.tenant.dto.TenantRequest;
import com.wealthview.core.tenant.dto.TenantResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/admin")
public class SuperAdminController {

    private final TenantService tenantService;

    public SuperAdminController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping("/tenants")
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody TenantRequest request) {
        var tenant = tenantService.createTenant(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.from(tenant));
    }

    @GetMapping("/tenants")
    public ResponseEntity<List<TenantResponse>> listTenants() {
        var tenants = tenantService.getAllTenants().stream()
                .map(TenantResponse::from)
                .toList();
        return ResponseEntity.ok(tenants);
    }

    @GetMapping("/tenants/details")
    public ResponseEntity<List<TenantDetailResponse>> listTenantDetails() {
        return ResponseEntity.ok(tenantService.getAllTenantDetails());
    }

    @GetMapping("/tenants/{id}")
    public ResponseEntity<TenantDetailResponse> getTenantDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.getTenantDetail(id));
    }

    @PutMapping("/tenants/{id}/active")
    public ResponseEntity<Void> updateTenantActive(
            @PathVariable UUID id,
            @Valid @RequestBody SetActiveRequest request) {
        tenantService.setTenantActive(id, request.active());
        return ResponseEntity.noContent().build();
    }
}
