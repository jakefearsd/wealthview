package com.wealthview.api.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.wealthview.core.exception.ServiceUnavailableException;
import com.wealthview.core.price.PriceService;
import com.wealthview.core.price.dto.BulkPriceRequest;
import com.wealthview.core.price.dto.CsvImportResult;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.core.price.dto.PriceSyncStatus;
import com.wealthview.core.price.dto.YahooFetchRequest;
import com.wealthview.core.price.dto.YahooSyncResult;
import com.wealthview.core.pricefeed.PriceSyncService;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminPriceController {

    @Nullable
    private final PriceSyncService priceSyncService;
    private final PriceService priceService;

    public AdminPriceController(@Nullable PriceSyncService priceSyncService,
                                PriceService priceService) {
        this.priceSyncService = priceSyncService;
        this.priceService = priceService;
    }

    @PostMapping("/prices/sync")
    public ResponseEntity<?> triggerPriceSync() {
        if (priceSyncService == null) {
            throw new ServiceUnavailableException(
                    "Finnhub API key is not configured. Set app.finnhub.api-key in your environment.");
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
        // NOTE: composing the symbol universe from getSyncStatus() is a business
        // decision that belongs in PriceService (core); move it there in a follow-up.
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
}
