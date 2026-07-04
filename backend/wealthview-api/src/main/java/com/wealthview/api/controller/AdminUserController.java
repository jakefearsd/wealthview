package com.wealthview.api.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.core.tenant.UserManagementService;
import com.wealthview.core.tenant.dto.AdminUserResponse;
import com.wealthview.core.tenant.dto.PasswordResetRequest;
import com.wealthview.core.tenant.dto.SetActiveRequest;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminUserController {

    private final UserManagementService userManagementService;

    public AdminUserController(UserManagementService userManagementService) {
        this.userManagementService = userManagementService;
    }

    @GetMapping("/users")
    public List<AdminUserResponse> getAllUsers() {
        return userManagementService.getAllUsers().stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    @PutMapping("/users/{userId}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID userId,
            @Valid @RequestBody PasswordResetRequest request) {
        userManagementService.resetPasswordByUserId(userId, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    // LinguisticNaming: a Spring controller handler named for its HTTP action must return
    // ResponseEntity, not void — the framework contract overrides the setter naming heuristic.
    @SuppressWarnings("PMD.LinguisticNaming")
    @PutMapping("/users/{userId}/active")
    public ResponseEntity<Void> setUserActive(@PathVariable UUID userId,
            @Valid @RequestBody SetActiveRequest request) {
        userManagementService.setUserActiveById(userId, request.active());
        return ResponseEntity.noContent().build();
    }
}
