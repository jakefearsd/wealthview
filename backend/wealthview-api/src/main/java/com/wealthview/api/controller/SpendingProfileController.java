package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.projection.SpendingProfileService;
import com.wealthview.core.projection.dto.CreateSpendingProfileRequest;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.UpdateSpendingProfileRequest;
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
@RequestMapping("/api/v1/spending-profiles")
public class SpendingProfileController {

    private final SpendingProfileService spendingProfileService;

    public SpendingProfileController(SpendingProfileService spendingProfileService) {
        this.spendingProfileService = spendingProfileService;
    }

    @PostMapping
    public ResponseEntity<SpendingProfileResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestBody CreateSpendingProfileRequest request) {
        var result = spendingProfileService.createProfile(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<SpendingProfileResponse>> list(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(spendingProfileService.listProfiles(principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SpendingProfileResponse> get(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(spendingProfileService.getProfile(principal.tenantId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SpendingProfileResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody UpdateSpendingProfileRequest request) {
        return ResponseEntity.ok(spendingProfileService.updateProfile(principal.tenantId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        spendingProfileService.deleteProfile(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
