package com.wealthview.api.controller;

import com.wealthview.core.mobile.MobileAppVersionService;
import com.wealthview.core.mobile.dto.VersionCheckResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * SUPER_ADMIN endpoints for managing the mobile-app version-check rows.
 *
 * <p>Path-based authorization is enforced in {@code SecurityConfig}'s
 * {@code /api/v1/admin/**} rule (SUPER_ADMIN only) so no method-level
 * {@code @PreAuthorize} is needed.
 */
@RestController
@RequestMapping("/api/v1/admin/mobile-versions")
public class MobileAppVersionAdminController {

    private final MobileAppVersionService service;

    public MobileAppVersionAdminController(MobileAppVersionService service) {
        this.service = service;
    }

    @GetMapping
    public List<VersionCheckResponse> list() {
        return service.listAll();
    }

    @PutMapping("/{platform}")
    public VersionCheckResponse update(@PathVariable String platform,
                                       @Valid @RequestBody UpdateRequest request) {
        return service.updateVersion(platform,
                request.minimumSupportedVersion(),
                request.latestVersion(),
                request.storeUrl(),
                request.message());
    }

    public record UpdateRequest(
            @NotBlank String minimumSupportedVersion,
            @NotBlank String latestVersion,
            @NotBlank String storeUrl,
            String message) {
    }
}
