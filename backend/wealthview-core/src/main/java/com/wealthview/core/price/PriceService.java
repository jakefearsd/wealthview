package com.wealthview.core.price;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.price.dto.BulkPriceRequest;
import com.wealthview.core.price.dto.CsvImportResult;
import com.wealthview.core.price.dto.PriceRequest;
import com.wealthview.core.price.dto.PriceResponse;
import com.wealthview.core.price.dto.PriceSyncStatus;
import com.wealthview.core.price.dto.YahooFetchRequest;
import com.wealthview.core.price.dto.YahooSyncResult;
import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PriceService {

    private static final Logger log = LoggerFactory.getLogger(PriceService.class);
    private static final String SOURCE_YAHOO = "yahoo";
    private static final String SOURCE_MANUAL = "manual";
    private static final int STALE_THRESHOLD_DAYS = 3;

    private final PriceRepository priceRepository;
    private final HoldingRepository holdingRepository;
    @Nullable
    private final YahooPriceClient yahooPriceClient;

    public PriceService(PriceRepository priceRepository,
                        HoldingRepository holdingRepository,
                        @Nullable YahooPriceClient yahooPriceClient) {
        this.priceRepository = priceRepository;
        this.holdingRepository = holdingRepository;
        this.yahooPriceClient = yahooPriceClient;
    }

    @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
    @Transactional
    public PriceResponse createPrice(PriceRequest request) {
        var price = new PriceEntity(request.symbol(), request.date(),
                request.closePrice(), SOURCE_MANUAL);
        price = priceRepository.save(price);
        return PriceResponse.from(price);
    }

    @Transactional(readOnly = true)
    public PriceResponse getLatestPrice(String symbol) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(symbol)
                .map(PriceResponse::from)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No price found for symbol: " + symbol));
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> listLatestPrices() {
        return priceRepository.findLatestPerSymbol().stream()
                .map(PriceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<PriceResponse> findLatestPrice(String symbol) {
        return priceRepository.findFirstBySymbolOrderByDateDesc(symbol)
                .map(PriceResponse::from);
    }

    @Transactional(readOnly = true)
    public List<PriceSyncStatus> getSyncStatus() {
        var symbols = holdingRepository.findDistinctSymbols();
        var staleThreshold = computeStaleThreshold();

        return symbols.stream()
                .map(symbol -> {
                    var latestOpt = priceRepository.findFirstBySymbolOrderByDateDesc(symbol);
                    if (latestOpt.isEmpty()) {
                        return new PriceSyncStatus(symbol, null, null, true);
                    }
                    var latest = latestOpt.orElseThrow();
                    boolean stale = latest.getDate().isBefore(staleThreshold);
                    return new PriceSyncStatus(symbol, latest.getDate(), latest.getSource(), stale);
                })
                .toList();
    }

    @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
    @Transactional
    public YahooSyncResult syncFromYahoo(List<String> symbols) {
        if (yahooPriceClient == null) {
            log.warn("Yahoo Finance client not configured; marking all symbols as failed");
            var failures = symbols.stream()
                    .map(s -> new YahooSyncResult.SymbolError(s, "Yahoo Finance client is not configured"))
                    .toList();
            return new YahooSyncResult(0, 0, new ArrayList<>(failures));
        }

        var today = LocalDate.now();
        var from = today.minusDays(5);
        int inserted = 0;
        int updated = 0;
        var failures = new ArrayList<YahooSyncResult.SymbolError>();

        for (var symbol : symbols) {
            var fetchResult = yahooPriceClient.fetchHistory(symbol, from, today);
            if (fetchResult.failed()) {
                failures.add(new YahooSyncResult.SymbolError(symbol, fetchResult.errorReason()));
                continue;
            }

            if (fetchResult.points().isEmpty()) {
                failures.add(new YahooSyncResult.SymbolError(symbol,
                        "no price data returned from Yahoo Finance"));
                continue;
            }

            for (var point : fetchResult.points()) {
                var upsertResult = upsertPrice(symbol, point.date(), point.closePrice(), SOURCE_YAHOO);
                if (upsertResult) {
                    updated++;
                } else {
                    inserted++;
                }
            }
        }

        log.info("Yahoo sync complete: {} inserted, {} updated, {} failed", inserted, updated, failures.size());
        return new YahooSyncResult(inserted, updated, failures);
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> fetchFromYahoo(YahooFetchRequest request) {
        if (yahooPriceClient == null) {
            throw new IllegalStateException("Yahoo Finance client is not configured");
        }

        var responses = new ArrayList<PriceResponse>();
        for (var symbol : request.symbols()) {
            var fetchResult = yahooPriceClient.fetchHistory(symbol, request.fromDate(), request.toDate());
            for (var point : fetchResult.points()) {
                responses.add(new PriceResponse(symbol, point.date(), point.closePrice(), SOURCE_YAHOO));
            }
        }
        return responses;
    }

    @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
    @Transactional
    public int bulkUpsertPrices(List<BulkPriceRequest.PriceEntry> prices, String source) {
        int count = 0;
        for (var entry : prices) {
            upsertPrice(entry.symbol(), entry.date(), entry.closePrice(), source);
            count++;
        }
        return count;
    }

    @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
    @Transactional
    public CsvImportResult importCsv(InputStream inputStream) throws IOException {
        var parser = new PriceCsvParser();
        var parseResult = parser.parse(inputStream);

        int imported = 0;
        for (var row : parseResult.rows()) {
            upsertPrice(row.symbol(), row.date(), row.closePrice(), SOURCE_MANUAL);
            imported++;
        }

        return new CsvImportResult(imported, parseResult.errors());
    }

    @Transactional(readOnly = true)
    public List<PriceResponse> browseSymbol(String symbol, LocalDate from, LocalDate to) {
        return priceRepository.findBySymbolAndDateBetweenOrderByDateDesc(symbol, from, to).stream()
                .map(PriceResponse::from)
                .toList();
    }

    @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
    @Transactional
    public void deletePrice(String symbol, LocalDate date) {
        var priceId = new PriceId(symbol, date);
        if (!priceRepository.existsById(priceId)) {
            throw new EntityNotFoundException(
                    "Price not found for symbol %s on %s".formatted(symbol, date));
        }
        priceRepository.deleteBySymbolAndDate(symbol, date);
        log.info("Deleted price for {} on {}", symbol, date);
    }

    /**
     * Upserts a price. Returns true if an existing record was updated, false if a new one was inserted.
     */
    private boolean upsertPrice(String symbol, LocalDate date, java.math.BigDecimal closePrice, String source) {
        var priceId = new PriceId(symbol, date);
        var existing = priceRepository.findById(priceId);

        if (existing.isPresent()) {
            var entity = existing.orElseThrow();
            entity.setClosePrice(closePrice);
            entity.setSource(source);
            priceRepository.save(entity);
            return true;
        } else {
            var entity = new PriceEntity(symbol, date, closePrice, source);
            priceRepository.save(entity);
            return false;
        }
    }

    private LocalDate computeStaleThreshold() {
        // A price is stale if it's older than 2 trading days ago.
        // Walk backwards from today, skipping weekends.
        var date = LocalDate.now();
        int tradingDays = 0;
        while (tradingDays < 2) {
            date = date.minusDays(1);
            if (date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY) {
                tradingDays++;
            }
        }
        return date;
    }
}
