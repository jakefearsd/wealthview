package com.wealthview.api.controller;

import com.wealthview.api.security.TenantUserPrincipal;
import com.wealthview.core.split.StockSplitService;
import com.wealthview.core.split.StockSplitSyncService;
import com.wealthview.core.split.dto.SplitSyncResult;
import com.wealthview.core.split.dto.StockSplitResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Stock-split endpoints. Authorization is enforced by {@code SecurityConfig}:
 *  - {@code /api/v1/admin/stock-splits/**} requires SUPER_ADMIN
 *  - {@code GET /api/v1/stock-splits} requires authentication only (any role)
 *
 * <p>The sync endpoint is conditional on the bean being present; it returns
 * 503 if Finnhub isn't configured.
 */
@RestController
@RequestMapping("/api/v1")
public class StockSplitController {

    private final StockSplitService stockSplitService;
    private final ObjectProvider<StockSplitSyncService> stockSplitSyncServiceProvider;

    public StockSplitController(StockSplitService stockSplitService,
                                ObjectProvider<StockSplitSyncService> stockSplitSyncServiceProvider) {
        this.stockSplitService = stockSplitService;
        this.stockSplitSyncServiceProvider = stockSplitSyncServiceProvider;
    }

    @GetMapping("/stock-splits")
    public List<StockSplitResponse> listForTenant(
            @AuthenticationPrincipal TenantUserPrincipal principal,
            @RequestParam(required = false) String symbol,
            @RequestParam(value = "from", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return stockSplitService.listForTenant(principal.tenantId(), symbol, from, to).stream()
                .map(StockSplitResponse::from)
                .toList();
    }

    @PostMapping("/admin/stock-splits")
    public ResponseEntity<StockSplitResponse> create(@Valid @RequestBody CreateSplitRequest request) {
        var split = stockSplitService.applySplit(
                request.symbol(), request.effectiveDate(),
                request.numerator(), request.denominator(), "manual");
        return ResponseEntity.status(HttpStatus.CREATED).body(StockSplitResponse.from(split));
    }

    @DeleteMapping("/admin/stock-splits/{id}")
    public ResponseEntity<Void> unapply(@PathVariable UUID id) {
        stockSplitService.unapplySplit(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/stock-splits/sync")
    public ResponseEntity<SplitSyncResult> sync() {
        var svc = stockSplitSyncServiceProvider.getIfAvailable();
        if (svc == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(svc.syncAll());
    }

    public record CreateSplitRequest(
            @NotBlank String symbol,
            @NotNull LocalDate effectiveDate,
            @Positive int numerator,
            @Positive int denominator) {
    }
}
