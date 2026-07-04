package com.wealthview.api.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.api.dto.ConfigValueRequest;
import com.wealthview.core.auth.LoginActivityService;
import com.wealthview.core.auth.dto.LoginActivityResponse;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.config.SystemStatsService;
import com.wealthview.core.config.dto.SystemConfigResponse;
import com.wealthview.core.config.dto.SystemStatsResponse;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminSystemController {

    private final SystemStatsService systemStatsService;
    private final LoginActivityService loginActivityService;
    private final SystemConfigService systemConfigService;

    public AdminSystemController(SystemStatsService systemStatsService,
                                 LoginActivityService loginActivityService,
                                 SystemConfigService systemConfigService) {
        this.systemStatsService = systemStatsService;
        this.loginActivityService = loginActivityService;
        this.systemConfigService = systemConfigService;
    }

    @GetMapping("/system-stats")
    public SystemStatsResponse getSystemStats() {
        return systemStatsService.getStats();
    }

    @GetMapping("/login-activity")
    public List<LoginActivityResponse> getLoginActivity(
            @RequestParam(defaultValue = "50") int limit) {
        return loginActivityService.getRecent(limit);
    }

    @GetMapping("/config")
    public List<SystemConfigResponse> getConfig() {
        return systemConfigService.getAll();
    }

    // LinguisticNaming: a Spring controller handler named for its HTTP action must return
    // ResponseEntity, not void — the framework contract overrides the setter naming heuristic.
    @SuppressWarnings("PMD.LinguisticNaming")
    @PutMapping("/config/{key}")
    public ResponseEntity<Void> setConfig(@PathVariable String key,
            @Valid @RequestBody ConfigValueRequest request) {
        systemConfigService.set(key, request.value());
        return ResponseEntity.noContent().build();
    }
}
