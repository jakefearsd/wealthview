package com.wealthview.core.pricefeed;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Service
@ConditionalOnExpression("!'${app.finnhub.api-key:}'.isEmpty()")
public class PriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncService.class);
    private static final String SOURCE_FINNHUB = "finnhub";

    private final PriceFeedClient priceFeedClient;
    private final PriceRepository priceRepository;
    private final HoldingRepository holdingRepository;
    private final long rateLimitMs;
    private final MeterRegistry meterRegistry;

    public PriceSyncService(PriceFeedClient priceFeedClient,
                            PriceRepository priceRepository,
                            HoldingRepository holdingRepository,
                            @Value("${app.finnhub.rate-limit-ms:1100}") long rateLimitMs,
                            MeterRegistry meterRegistry) {
        this.priceFeedClient = priceFeedClient;
        this.priceRepository = priceRepository;
        this.holdingRepository = holdingRepository;
        this.rateLimitMs = rateLimitMs;
        this.meterRegistry = meterRegistry;
    }

    @CacheEvict(value = {"latestPrices", "accountBalances"}, allEntries = true)
    @Timed("wealthview.pricefeed.sync")
    @SuppressWarnings("PMD.AvoidCatchingGenericException") // intentional per-symbol resilience (logs and continues loop)
    @Scheduled(cron = "${app.finnhub.sync-cron:0 0 18 * * MON-FRI}", zone = "America/New_York")
    public void syncDailyPrices() {
        MDC.put("operation", "priceSync");
        MDC.put("requestId", UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        try {
            long startTime = System.currentTimeMillis();
            // Prices are shared reference data — intentionally aggregated across all tenants
            var symbols = holdingRepository.findDistinctSymbols();
            log.info("Starting daily price sync for {} symbols", symbols.size());

            int successCount = 0;
            int failCount = 0;
            for (var symbol : symbols) {
                try {
                    var quoteOpt = priceFeedClient.getQuote(symbol);
                    if (quoteOpt.isEmpty()) {
                        log.warn("No quote returned for symbol {}", symbol);
                        failCount++;
                        continue;
                    }

                    var quote = quoteOpt.orElseThrow();
                    upsertPrice(symbol, LocalDate.now(), quote.currentPrice(), SOURCE_FINNHUB);
                    successCount++;
                    sleepForRateLimit();
                } catch (Exception e) {
                    log.warn("Failed to sync price for symbol {}", symbol, e);
                    failCount++;
                }
            }

            meterRegistry.counter("wealthview.pricefeed.symbols", "status", "success").increment(successCount);
            meterRegistry.counter("wealthview.pricefeed.symbols", "status", "failure").increment(failCount);
            log.info("Daily price sync complete: {} succeeded, {} failed, {}ms",
                    successCount, failCount, System.currentTimeMillis() - startTime);
        } finally {
            MDC.remove("operation");
            MDC.remove("requestId");
        }
    }

    public void backfillHistoricalPrices(String symbol) {
        MDC.put("operation", "priceBackfill");
        MDC.put("symbol", symbol);
        try {
            if (priceRepository.existsBySymbol(symbol)) {
                log.info("Prices already exist for symbol {}, skipping backfill", symbol);
                return;
            }

            long startTime = System.currentTimeMillis();
            log.info("Starting historical backfill for symbol {}", symbol);
            var to = LocalDate.now();
            var from = to.minusYears(2);

            var candlesOpt = priceFeedClient.getCandles(symbol, from, to);
            if (candlesOpt.isEmpty()) {
                log.warn("No candle data returned for symbol {}", symbol);
                return;
            }

            var candles = candlesOpt.orElseThrow();
            for (var entry : candles.entries()) {
                try {
                    upsertPrice(symbol, entry.date(), entry.closePrice(), SOURCE_FINNHUB);
                } catch (DataAccessException e) {
                    log.warn("Failed to save candle for {} on {}", symbol, entry.date(), e);
                }
            }

            log.info("Historical backfill complete for symbol {}: {} entries, {}ms",
                    symbol, candles.entries().size(), System.currentTimeMillis() - startTime);
        } finally {
            MDC.remove("operation");
            MDC.remove("symbol");
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewHolding(NewHoldingCreatedEvent event) {
        backfillHistoricalPrices(event.symbol());
    }

    private void upsertPrice(String symbol, LocalDate date, BigDecimal closePrice, String source) {
        var priceId = new PriceId(symbol, date);
        var existing = priceRepository.findById(priceId);

        if (existing.isPresent()) {
            var entity = existing.orElseThrow();
            entity.setClosePrice(closePrice);
            entity.setSource(source);
            priceRepository.save(entity);
        } else {
            var entity = new PriceEntity(symbol, date, closePrice, source);
            priceRepository.save(entity);
        }
    }

    private void sleepForRateLimit() {
        if (rateLimitMs > 0) {
            try {
                Thread.sleep(rateLimitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Rate limit sleep interrupted");
            }
        }
    }
}
