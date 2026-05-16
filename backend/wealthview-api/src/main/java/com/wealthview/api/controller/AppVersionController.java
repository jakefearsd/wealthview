package com.wealthview.api.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.core.mobile.MobileAppVersionService;
import com.wealthview.core.mobile.dto.VersionCheckResponse;

/**
 * Anonymous version-check endpoint hit by mobile clients on app launch.
 *
 * <p>Returns whether the running build is force-update-required, soft-update
 * recommended, or up-to-date relative to the operator-set floor and ceiling
 * for the platform. No auth — the app may not have credentials yet, and the
 * response reveals nothing sensitive (just the version-policy values an
 * attacker could read by inspecting the published store binary anyway).
 *
 * <p>The matching admin-only management endpoints live in
 * {@link MobileAppVersionAdminController}.
 */
@RestController
@RequestMapping("/api/v1/app")
public class AppVersionController {

    private final MobileAppVersionService service;

    public AppVersionController(MobileAppVersionService service) {
        this.service = service;
    }

    @GetMapping("/version-check")
    public VersionCheckResponse versionCheck(@RequestParam(name = "platform", required = false) String platform,
                                             @RequestParam(name = "version", required = false) String version) {
        if (platform == null) {
            throw new IllegalArgumentException("platform query parameter is required");
        }
        if (version == null) {
            throw new IllegalArgumentException("version query parameter is required");
        }
        return service.versionCheck(platform, version);
    }
}
