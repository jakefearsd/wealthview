package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.property.PropertyAnalyticsService;
import com.wealthview.core.property.PropertyService;
import com.wealthview.core.property.PropertyValuationService;
import com.wealthview.core.property.PropertyValuationSyncService;
import com.wealthview.core.property.dto.MonthlyCashFlowDetailEntry;
import com.wealthview.core.property.dto.MonthlyCashFlowEntry;
import com.wealthview.core.property.dto.PropertyAnalyticsResponse;
import com.wealthview.core.property.dto.PropertyExpenseRequest;
import com.wealthview.core.property.dto.PropertyExpenseResponse;
import com.wealthview.core.property.dto.PropertyRequest;
import com.wealthview.core.property.dto.PropertyResponse;
import com.wealthview.core.property.dto.PropertyValuationResponse;
import com.wealthview.core.property.dto.SelectZpidRequest;
import com.wealthview.core.property.dto.ValuationRefreshResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/properties")
public class PropertyController {

    private final PropertyService propertyService;
    private final PropertyValuationService valuationService;
    private final PropertyAnalyticsService analyticsService;
    private final @Nullable PropertyValuationSyncService syncService;

    public PropertyController(PropertyService propertyService,
                              PropertyValuationService valuationService,
                              PropertyAnalyticsService analyticsService,
                              @Nullable PropertyValuationSyncService syncService) {
        this.propertyService = propertyService;
        this.valuationService = valuationService;
        this.analyticsService = analyticsService;
        this.syncService = syncService;
    }

    @PostMapping
    public ResponseEntity<PropertyResponse> create(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @Valid @RequestBody PropertyRequest request) {
        var response = propertyService.create(principal.tenantId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PropertyResponse>> list(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        return ResponseEntity.ok(propertyService.list(principal.tenantId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PropertyResponse> get(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(propertyService.get(principal.tenantId(), id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PropertyResponse> update(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody PropertyRequest request) {
        return ResponseEntity.ok(propertyService.update(principal.tenantId(), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        propertyService.delete(principal.tenantId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/expenses")
    public ResponseEntity<List<PropertyExpenseResponse>> listExpenses(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(propertyService.listExpenses(principal.tenantId(), id));
    }

    @DeleteMapping("/{id}/expenses/{expenseId}")
    public ResponseEntity<Void> deleteExpense(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @PathVariable UUID expenseId) {
        propertyService.deleteExpense(principal.tenantId(), id, expenseId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/expenses")
    public ResponseEntity<Void> addExpense(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody PropertyExpenseRequest request) {
        propertyService.addExpense(principal.tenantId(), id, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @GetMapping("/{id}/cashflow")
    public ResponseEntity<List<MonthlyCashFlowEntry>> getCashFlow(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam String from,
            @RequestParam String to) {
        var fromMonth = YearMonth.parse(from);
        var toMonth = YearMonth.parse(to);
        var cashFlow = propertyService.getMonthlyCashFlow(
                principal.tenantId(), id, fromMonth, toMonth);
        return ResponseEntity.ok(cashFlow);
    }

    @GetMapping("/{id}/cashflow-detail")
    public ResponseEntity<List<MonthlyCashFlowDetailEntry>> getCashFlowDetail(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam String from,
            @RequestParam String to) {
        var fromMonth = YearMonth.parse(from);
        var toMonth = YearMonth.parse(to);
        var detail = propertyService.getMonthlyCashFlowDetail(
                principal.tenantId(), id, fromMonth, toMonth);
        return ResponseEntity.ok(detail);
    }

    @GetMapping("/{id}/analytics")
    public ResponseEntity<PropertyAnalyticsResponse> getAnalytics(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer year) {
        return ResponseEntity.ok(analyticsService.getAnalytics(principal.tenantId(), id, year));
    }

    @GetMapping("/{id}/valuations")
    public ResponseEntity<List<PropertyValuationResponse>> getValuations(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        return ResponseEntity.ok(valuationService.getHistory(principal.tenantId(), id));
    }

    @PostMapping("/{id}/valuations/refresh")
    public ResponseEntity<ValuationRefreshResponse> refreshValuation(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id) {
        if (syncService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        var result = syncService.refreshProperty(principal.tenantId(), id);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{id}/valuations/select-zpid")
    public ResponseEntity<ValuationRefreshResponse> selectZpid(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @PathVariable UUID id,
            @Valid @RequestBody SelectZpidRequest request) {
        if (syncService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        var result = syncService.selectZpid(principal.tenantId(), id, request.zpid());
        return ResponseEntity.ok(result);
    }
}
