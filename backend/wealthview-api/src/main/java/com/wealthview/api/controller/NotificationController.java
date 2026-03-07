package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.notification.NotificationPreferenceService;
import com.wealthview.core.notification.dto.NotificationPreferenceRequest;
import com.wealthview.core.notification.dto.NotificationPreferenceResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications/preferences")
public class NotificationController {

    private final NotificationPreferenceService preferenceService;

    public NotificationController(NotificationPreferenceService preferenceService) {
        this.preferenceService = preferenceService;
    }

    @GetMapping
    public ResponseEntity<List<NotificationPreferenceResponse>> getPreferences(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(preferenceService.getPreferences(principal.userId()));
    }

    @PutMapping
    public ResponseEntity<Void> updatePreferences(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody NotificationPreferenceRequest request) {
        preferenceService.updatePreferences(principal.userId(), request);
        return ResponseEntity.ok().build();
    }
}
