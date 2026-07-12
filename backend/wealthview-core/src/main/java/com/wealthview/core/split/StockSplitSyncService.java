package com.wealthview.core.split;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.split.dto.DetectedSplit;
import com.wealthview.core.split.dto.SplitSyncResult;
import com.wealthview.persistence.repository.StockSplitRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Daily scheduled job: scan every distinct symbol the WealthView deployment
 * holds, fetch any new splits from Finnhub since the last successful run
 * (with a 7-day overlap for safety), and apply them via
 * {@link StockSplitService}.
 *
 * <p>Conditional on a {@link SplitDetectionClient} bean being present, which
 * itself is conditional on {@code app.finnhub.api-key} being non-empty.
 */
@Service
@ConditionalOnBean(SplitDetectionClient.class)
public class StockSplitSyncService {

    private static final Logger log = LoggerFactory.getLogger(StockSplitSyncService.class);
    private static final String LAST_SYNC_KEY = "stock_splits.last_sync_at";
    private static final String SOURCE = "finnhub";

    private final SplitDetectionClient splitDetectionClient;
    private final StockSplitService stockSplitService;
    private final StockSplitRepository stockSplitRepository;
    private final TransactionRepository transactionRepository;
    private final SystemConfigService systemConfigService;
    private final MeterRegistry meterRegistry;
    private final AtomicLong lastSuccessSeconds = new AtomicLong(0);

    public StockSplitSyncService(SplitDetectionClient splitDetectionClient,
                                 StockSplitService stockSplitService,
                                 StockSplitRepository stockSplitRepository,
                                 TransactionRepository transactionRepository,
                                 SystemConfigService systemConfigService,
                                 MeterRegistry meterRegistry) {
        this.splitDetectionClient = splitDetectionClient;
        this.stockSplitService = stockSplitService;
        this.stockSplitRepository = stockSplitRepository;
        this.transactionRepository = transactionRepository;
        this.systemConfigService = systemConfigService;
        this.meterRegistry = meterRegistry;
        io.micrometer.core.instrument.Gauge.builder(
                        "wealthview.splits.last_success_seconds",
                        lastSuccessSeconds, AtomicLong::doubleValue)
                .description("Unix epoch seconds of last successful split sync; 0 if never")
                .register(meterRegistry);
    }

    @Timed(value = "wealthview.splits.sync", histogram = true)
    @Scheduled(cron = "${app.stock-splits.sync-cron:0 0 2 * * *}", zone = "America/New_York")
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public SplitSyncResult syncAll() {
        var symbols = transactionRepository.findDistinctSymbolsAcrossAllTenants();
        var fromDate = computeFromDate();
        var toDate = LocalDate.now();
        log.info("Starting stock split sync: {} symbols, window {} to {}", symbols.size(), fromDate, toDate);

        int applied = 0;
        int discovered = 0;
        var failures = new ArrayList<String>();

        for (var symbol : symbols) {
            try {
                var splits = splitDetectionClient.fetch(symbol, fromDate, toDate);
                for (var s : splits) {
                    discovered++;
                    if (!stockSplitRepository.existsBySymbolAndEffectiveDate(symbol, s.date())) {
                        stockSplitService.applySplit(symbol, s.date(), s.numerator(), s.denominator(), SOURCE);
                        applied++;
                        warnIfSplitAffectedNoTenants(symbol, s);
                    }
                }
            } catch (Exception e) {
                log.warn("Split sync failed for {}: {}", symbol, e.getMessage());
                failures.add(symbol);
                meterRegistry.counter("wealthview.splits.sync_failed", "symbol", symbol).increment();
            }
        }

        systemConfigService.set(LAST_SYNC_KEY, Instant.now().toString());
        lastSuccessSeconds.set(Instant.now().getEpochSecond());

        meterRegistry.counter("wealthview.splits.synced_total", "result",
                failures.isEmpty() ? "success" : "partial").increment();

        log.info("Split sync complete: {} symbols, {} discovered, {} applied, {} failed",
                symbols.size(), discovered, applied, failures.size());
        return new SplitSyncResult(symbols.size(), discovered, applied, failures);
    }

    /**
     * {@code symbol} reached this point via {@link TransactionRepository#findDistinctSymbolsAcrossAllTenants()}
     * moments earlier in this same run, so by definition at least one tenant held it then. If
     * {@link StockSplitService#applySplit} reports zero affected tenants for that exact symbol
     * immediately afterward, the two reads disagree about the same committed data — unlike an
     * admin-driven apply for a symbol nobody currently holds (a legitimate, documented case),
     * that is never expected for a sync-discovered split. The split row is still recorded (so a
     * future sync's {@code existsBySymbolAndEffectiveDate} check will not re-detect it), but
     * holdings for the symbol may be un-adjusted; surface that loudly instead of letting it
     * vanish silently into "splits applied".
     */
    private void warnIfSplitAffectedNoTenants(String symbol, DetectedSplit split) {
        if (transactionRepository.findDistinctTenantIdsBySymbol(symbol).isEmpty()) {
            log.warn("Sync applied split {}:{} for {} on {} but found ZERO tenants holding {} "
                    + "immediately afterward, despite {} being discovered by this run's own "
                    + "distinct-symbol scan — holdings may not be split-adjusted; investigate before "
                    + "trusting balances for this symbol",
                    split.numerator(), split.denominator(), symbol, split.date(), symbol, symbol);
        }
    }

    private LocalDate computeFromDate() {
        var stored = systemConfigService.get(LAST_SYNC_KEY);
        if (stored == null) {
            // First run — go back 7 days from today.
            return LocalDate.now().minusDays(7);
        }
        try {
            return Instant.parse(stored).atZone(ZoneId.systemDefault())
                    .toLocalDate().minusDays(7);
        } catch (DateTimeParseException e) {
            log.warn("Unparseable stock_splits.last_sync_at='{}', defaulting to -7 days", stored);
            return LocalDate.now().minusDays(7);
        }
    }
}
