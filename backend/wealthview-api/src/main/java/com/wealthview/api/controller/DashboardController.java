package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.dashboard.CombinedPortfolioHistoryService;
import com.wealthview.core.dashboard.DashboardService;
import com.wealthview.core.dashboard.SnapshotProjectionService;
import com.wealthview.core.dashboard.dto.CombinedPortfolioHistoryResponse;
import com.wealthview.core.dashboard.dto.DashboardSummaryResponse;
import com.wealthview.core.dashboard.dto.SnapshotProjectionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final CombinedPortfolioHistoryService combinedPortfolioHistoryService;
    private final SnapshotProjectionService snapshotProjectionService;

    public DashboardController(DashboardService dashboardService,
                                CombinedPortfolioHistoryService combinedPortfolioHistoryService,
                                SnapshotProjectionService snapshotProjectionService) {
        this.dashboardService = dashboardService;
        this.combinedPortfolioHistoryService = combinedPortfolioHistoryService;
        this.snapshotProjectionService = snapshotProjectionService;
    }

    @GetMapping("/summary")
    public ResponseEntity<DashboardSummaryResponse> getSummary(
            @AuthenticationPrincipal TenantUserPrincipal principal) {
        var summary = dashboardService.getSummary(principal.tenantId());
        return ResponseEntity.ok(summary);
    }

    @GetMapping("/portfolio-history")
    public ResponseEntity<CombinedPortfolioHistoryResponse> getPortfolioHistory(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam(defaultValue = "2") int years) {
        var response = combinedPortfolioHistoryService.computeHistory(
                principal.tenantId(), years);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/snapshot-projection")
    public ResponseEntity<SnapshotProjectionResponse> getSnapshotProjection(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam(defaultValue = "10") int years,
            @RequestParam(defaultValue = "10") int lookback) {
        var response = snapshotProjectionService.computeProjection(
                principal.tenantId(), years, lookback);
        return ResponseEntity.ok(response);
    }
}
