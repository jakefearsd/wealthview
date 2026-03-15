package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.projection.GuardrailProfileService;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projections/{scenarioId}")
public class GuardrailController {

    private static final Logger log = LoggerFactory.getLogger(GuardrailController.class);

    private final GuardrailProfileService guardrailProfileService;

    public GuardrailController(GuardrailProfileService guardrailProfileService) {
        this.guardrailProfileService = guardrailProfileService;
    }

    @PostMapping("/optimize")
    public ResponseEntity<GuardrailProfileResponse> optimize(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID scenarioId,
            @RequestBody GuardrailOptimizationRequest request) {
        log.info("Running guardrail optimization for scenario {} tenant {}",
                scenarioId, principal.tenantId());
        var result = guardrailProfileService.optimize(principal.tenantId(), scenarioId, request);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/guardrail")
    public ResponseEntity<GuardrailProfileResponse> getGuardrail(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID scenarioId) {
        return ResponseEntity.ok(
                guardrailProfileService.getGuardrailProfile(principal.tenantId(), scenarioId));
    }

    @DeleteMapping("/guardrail")
    public ResponseEntity<Void> deleteGuardrail(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID scenarioId) {
        guardrailProfileService.deleteGuardrailProfile(principal.tenantId(), scenarioId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/guardrail/reoptimize")
    public ResponseEntity<GuardrailProfileResponse> reoptimize(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID scenarioId) {
        log.info("Re-optimizing guardrail for scenario {} tenant {}", scenarioId, principal.tenantId());
        return ResponseEntity.ok(
                guardrailProfileService.reoptimize(principal.tenantId(), scenarioId));
    }
}
