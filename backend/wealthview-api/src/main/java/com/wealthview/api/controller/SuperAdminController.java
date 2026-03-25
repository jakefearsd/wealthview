package com.wealthview.api.controller;

import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.BulkPriceRequest;
import com.wealthview.core.price.dto.CsvImportResult;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.core.price.dto.PriceSyncStatus;
import com.wealthview.core.price.dto.YahooFetchRequest;
import com.wealthview.core.price.dto.YahooSyncResult;
import com.wealthview.core.pricefeed.PriceSyncService;
import com.wealthview.core.tenant.TenantService;
import org.springframework.lang.Nullable;
import com.wealthview.core.tenant.dto.SetActiveRequest;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.core.tenant.dto.TenantRequest;
import com.wealthview.core.tenant.dto.TenantResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class SuperAdminController {

    private final TenantService tenantService;
    @Nullable
    private final PriceSyncService priceSyncService;
    private final PriceService priceService;

    public SuperAdminController(TenantService tenantService,
                                @Nullable PriceSyncService priceSyncService,
                                PriceService priceService) {
        this.tenantService = tenantService;
        this.priceSyncService = priceSyncService;
        this.priceService = priceService;
    }

    @PostMapping("/tenants")
    public ResponseEntity<TenantResponse> createTenant(@Valid @RequestBody TenantRequest request) {
        var tenant = tenantService.createTenant(request.name());
        return ResponseEntity.status(HttpStatus.CREATED).body(TenantResponse.from(tenant));
    }

    @GetMapping("/tenants")
    public ResponseEntity<List<TenantResponse>> listTenants() {
        var tenants = tenantService.getAllTenants().stream()
                .map(TenantResponse::from)
                .toList();
        return ResponseEntity.ok(tenants);
    }

    @GetMapping("/tenants/details")
    public ResponseEntity<List<TenantDetailResponse>> listTenantDetails() {
        return ResponseEntity.ok(tenantService.getAllTenantDetails());
    }

    @GetMapping("/tenants/{id}")
    public ResponseEntity<TenantDetailResponse> getTenantDetail(@PathVariable UUID id) {
        return ResponseEntity.ok(tenantService.getTenantDetail(id));
    }

    @PutMapping("/tenants/{id}/active")
    public ResponseEntity<Void> updateTenantActive(
            @PathVariable UUID id,
            @Valid @RequestBody SetActiveRequest request) {
        tenantService.setTenantActive(id, request.active());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/prices/sync")
    public ResponseEntity<Void> triggerPriceSync() {
        if (priceSyncService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        priceSyncService.syncDailyPrices();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/prices/status")
    public List<PriceSyncStatus> getPriceStatus() {
        return priceService.getSyncStatus();
    }

    @PostMapping("/prices/yahoo/sync")
    public ResponseEntity<YahooSyncResult> syncFromYahoo() {
        var symbols = priceService.getSyncStatus().stream()
                .map(PriceSyncStatus::symbol)
                .toList();
        var result = priceService.syncFromYahoo(symbols);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/prices/yahoo/fetch")
    public List<PriceResponse> fetchFromYahoo(@Valid @RequestBody YahooFetchRequest request) {
        return priceService.fetchFromYahoo(request);
    }

    @PostMapping("/prices/yahoo/save")
    public ResponseEntity<Void> saveYahooPrices(@Valid @RequestBody BulkPriceRequest request) {
        priceService.bulkUpsertPrices(request.prices(), "yahoo");
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/prices/csv")
    public ResponseEntity<CsvImportResult> importCsv(@RequestParam("file") MultipartFile file)
            throws IOException {
        var result = priceService.importCsv(file.getInputStream());
        return ResponseEntity.ok(result);
    }
}
