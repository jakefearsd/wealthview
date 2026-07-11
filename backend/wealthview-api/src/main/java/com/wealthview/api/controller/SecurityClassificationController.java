package com.wealthview.api.controller;

import java.util.Locale;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.wealthview.api.dto.ClassificationRequest;
import com.wealthview.api.dto.SecurityClassificationResponse;
import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.projection.SecurityClassificationService;
import com.wealthview.core.projection.dto.AssetClass;

@RestController
@RequestMapping("/api/v1/securities")
public class SecurityClassificationController {

    private final SecurityClassificationService classificationService;

    public SecurityClassificationController(SecurityClassificationService classificationService) {
        this.classificationService = classificationService;
    }

    // LinguisticNaming: a Spring controller handler named for its HTTP action must return
    // ResponseEntity, not void — the framework contract overrides the setter naming heuristic.
    @SuppressWarnings("PMD.LinguisticNaming")
    @PutMapping("/{symbol}/classification")
    public ResponseEntity<SecurityClassificationResponse> setClassification(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable String symbol,
            @Valid @RequestBody ClassificationRequest request) {
        AssetClass assetClass;
        try {
            assetClass = AssetClass.fromKey(request.assetClass().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown asset_class: " + request.assetClass(), e);
        }
        var saved = classificationService.setOverride(principal.tenantId(), symbol, assetClass);
        return ResponseEntity.ok(new SecurityClassificationResponse(symbol, saved.key()));
    }
}
