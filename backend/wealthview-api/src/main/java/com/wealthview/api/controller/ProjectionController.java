package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.projection.ProjectionService;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@RequestMapping("/api/v1/projections")
public class ProjectionController {

    private static final Logger log = LoggerFactory.getLogger(ProjectionController.class);

    private final ProjectionService projectionService;

    public ProjectionController(ProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @PostMapping
    public ResponseEntity<ScenarioResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestBody CreateScenarioRequest request) {
        log.info("Creating projection scenario '{}' for tenant {} with {} accounts",
                request.name(), principal.tenantId(), request.accounts() != null ? request.accounts().size() : 0);
        var result = projectionService.createScenario(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    @GetMapping
    public ResponseEntity<List<ScenarioResponse>> list(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(projectionService.listScenarios(principal.tenantId()));
    }

    @PostMapping("/compare")
    public ResponseEntity<CompareResponse> compare(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody CompareRequest request) {
        return ResponseEntity.ok(projectionService.compareScenarios(principal.tenantId(), request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ScenarioResponse> get(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(projectionService.getScenario(principal.tenantId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ScenarioResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @RequestBody UpdateScenarioRequest request) {
        return ResponseEntity.ok(projectionService.updateScenario(principal.tenantId(), id, request));
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
