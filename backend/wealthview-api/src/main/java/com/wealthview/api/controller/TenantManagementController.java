package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.UserManagementService;
import com.wealthview.core.tenant.dto.InviteCodeResponse;
import com.wealthview.core.tenant.dto.UpdateRoleRequest;
import com.wealthview.core.tenant.dto.UserResponse;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/tenant")
public class TenantManagementController {

    private final TenantService tenantService;
    private final UserManagementService userManagementService;

    public TenantManagementController(TenantService tenantService,
                                      UserManagementService userManagementService) {
        this.tenantService = tenantService;
        this.userManagementService = userManagementService;
    }

    @PostMapping("/invite-codes")
    public ResponseEntity<InviteCodeResponse> generateInviteCode(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        var invite = tenantService.generateInviteCode(principal.tenantId(), principal.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(InviteCodeResponse.from(invite));
    }

    @GetMapping("/invite-codes")
    public ResponseEntity<List<InviteCodeResponse>> listInviteCodes(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        var codes = tenantService.getInviteCodes(principal.tenantId()).stream()
                .map(InviteCodeResponse::from)
                .toList();
        return ResponseEntity.ok(codes);
    }

    @GetMapping("/users")
    public ResponseEntity<List<UserResponse>> listUsers(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        var users = userManagementService.getUsersForTenant(principal.tenantId()).stream()
                .map(UserResponse::from)
                .toList();
        return ResponseEntity.ok(users);
    }

    @PutMapping("/users/{id}/role")
    public ResponseEntity<UserResponse> updateUserRole(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateRoleRequest request) {
        var user = userManagementService.updateUserRole(principal.tenantId(), id, request.role());
        return ResponseEntity.ok(UserResponse.from(user));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        userManagementService.deleteUser(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }
}
