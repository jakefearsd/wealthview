package com.wealthview.api.controller;

import com.wealthview.core.auth.LoginActivityService;
import com.wealthview.core.auth.dto.LoginActivityResponse;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.config.SystemStatsService;
import com.wealthview.core.config.dto.SystemConfigResponse;
import com.wealthview.core.config.dto.SystemStatsResponse;
import com.wealthview.api.dto.ErrorResponse;
import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.BulkPriceRequest;
import com.wealthview.core.price.dto.CsvImportResult;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.core.price.dto.PriceSyncStatus;
import com.wealthview.core.price.dto.YahooFetchRequest;
import com.wealthview.core.price.dto.YahooSyncResult;
import com.wealthview.core.pricefeed.PriceSyncService;
import com.wealthview.core.pricefeed.dto.FinnhubSyncResult;
import com.wealthview.core.tenant.TenantService;
import com.wealthview.core.tenant.UserManagementService;
import com.wealthview.core.tenant.dto.AdminUserResponse;
import com.wealthview.core.tenant.dto.PasswordResetRequest;
import com.wealthview.core.tenant.dto.SetActiveRequest;
import com.wealthview.core.tenant.dto.TenantDetailResponse;
import com.wealthview.core.tenant.dto.TenantRequest;
import com.wealthview.core.tenant.dto.TenantResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class SuperAdminController {

    private final TenantService tenantService;
    @Nullable
    private final PriceSyncService priceSyncService;
    private final PriceService priceService;
    private final SystemStatsService systemStatsService;
    private final LoginActivityService loginActivityService;
    private final UserManagementService userManagementService;
    private final SystemConfigService systemConfigService;

    public SuperAdminController(TenantService tenantService,
                                @Nullable PriceSyncService priceSyncService,
                                PriceService priceService,
                                SystemStatsService systemStatsService,
                                LoginActivityService loginActivityService,
                                UserManagementService userManagementService,
                                SystemConfigService systemConfigService) {
        this.tenantService = tenantService;
        this.priceSyncService = priceSyncService;
        this.priceService = priceService;
        this.systemStatsService = systemStatsService;
        this.loginActivityService = loginActivityService;
        this.userManagementService = userManagementService;
        this.systemConfigService = systemConfigService;
    }

    // --- Tenant CRUD ---

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

    // --- Price endpoints ---

    @PostMapping("/prices/sync")
    public ResponseEntity<?> triggerPriceSync() {
        if (priceSyncService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ErrorResponse("SERVICE_UNAVAILABLE",
                            "Finnhub API key is not configured. Set app.finnhub.api-key in your environment.",
                            503));
        }
        var result = priceSyncService.syncDailyPrices();
        return ResponseEntity.ok(result);
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

    @GetMapping("/prices/{symbol}/history")
    public List<PriceResponse> browseSymbolPrices(@PathVariable String symbol,
            @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return priceService.browseSymbol(symbol, from, to);
    }

    @DeleteMapping("/prices/{symbol}/{date}")
    public ResponseEntity<Void> deletePrice(@PathVariable String symbol,
            @PathVariable LocalDate date) {
        priceService.deletePrice(symbol, date);
        return ResponseEntity.noContent().build();
    }

    // --- System stats ---

    @GetMapping("/system-stats")
    public SystemStatsResponse getSystemStats() {
        return systemStatsService.getStats();
    }

    // --- Login activity ---

    @GetMapping("/login-activity")
    public List<LoginActivityResponse> getLoginActivity(
            @RequestParam(defaultValue = "50") int limit) {
        return loginActivityService.getRecent(limit);
    }

    // --- User management (super_admin level) ---

    @GetMapping("/users")
    public List<AdminUserResponse> getAllUsers() {
        return userManagementService.getAllUsers().stream()
                .map(AdminUserResponse::from)
                .toList();
    }

    @PutMapping("/users/{userId}/password")
    public ResponseEntity<Void> resetPassword(@PathVariable UUID userId,
            @Valid @RequestBody PasswordResetRequest request) {
        userManagementService.resetPasswordByUserId(userId, request.newPassword());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{userId}/active")
    public ResponseEntity<Void> setUserActive(@PathVariable UUID userId,
            @RequestBody Map<String, Boolean> body) {
        var active = body.get("active");
        userManagementService.setUserActiveById(userId, Boolean.TRUE.equals(active));
        return ResponseEntity.noContent().build();
    }

    // --- System config ---

    @GetMapping("/config")
    public List<SystemConfigResponse> getConfig() {
        return systemConfigService.getAll();
    }

    @PutMapping("/config/{key}")
    public ResponseEntity<Void> setConfig(@PathVariable String key,
            @RequestBody Map<String, String> body) {
        var value = body.get("value");
        systemConfigService.set(key, value);
        return ResponseEntity.noContent().build();
    }
}
