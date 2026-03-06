package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.projection.ProjectionService;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projections")
public class ProjectionController {

    private final ProjectionService projectionService;

    public ProjectionController(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @PostMapping
    public ResponseEntity<ScenarioResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestBody CreateScenarioRequest request) {
        var result = projectionService.createScenario(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<ScenarioResponse>> list(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(projectionService.listScenarios(principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScenarioResponse> get(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(projectionService.getScenario(principal.tenantId(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        projectionService.deleteScenario(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/run")
    public ResponseEntity<ProjectionResultResponse> run(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(projectionService.runProjection(principal.tenantId(), id));
    }
}
