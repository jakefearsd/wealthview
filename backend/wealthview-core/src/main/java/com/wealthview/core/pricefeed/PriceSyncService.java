package com.wealthview.core.pricefeed;

import com.wealthview.persistence.entity.PriceEntity;
import com.wealthview.persistence.entity.PriceId;
import com.wealthview.persistence.repository.HoldingRepository;
import com.wealthview.persistence.repository.PriceRepository;
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

@Service
@ConditionalOnExpression("!'${app.finnhub.api-key:}'.isEmpty()")
public class PriceSyncService {

    private static final Logger log = LoggerFactory.getLogger(PriceSyncService.class);
    private static final String SOURCE_FINNHUB = "finnhub";

    private final PriceFeedClient priceFeedClient;
    private final PriceRepository priceRepository;
    private final HoldingRepository holdingRepository;
    private final long rateLimitMs;

    public PriceSyncService(PriceFeedClient priceFeedClient,
                            PriceRepository priceRepository,
                            HoldingRepository holdingRepository,
                            @Value("${app.finnhub.rate-limit-ms:1100}") long rateLimitMs) {
        this.priceFeedClient = priceFeedClient;
        this.priceRepository = priceRepository;
        this.holdingRepository = holdingRepository;
        this.rateLimitMs = rateLimitMs;
    }

    @Scheduled(cron = "${app.finnhub.sync-cron:0 0 18 * * MON-FRI}", zone = "America/New_York")
    public void syncDailyPrices() {
        var symbols = holdingRepository.findDistinctSymbols();
        log.info("Starting daily price sync for {} symbols", symbols.size());

        for (var symbol : symbols) {
            try {
                var quote = priceFeedClient.getQuote(symbol);
                if (quote.isEmpty()) {
                    log.warn("No quote returned for symbol {}", symbol);
                    continue;
                }

                upsertPrice(symbol, LocalDate.now(), quote.get().currentPrice(), SOURCE_FINNHUB);
                sleepForRateLimit();
            } catch (Exception e) {
                log.warn("Failed to sync price for symbol {}: {}", symbol, e.getMessage());
            }
        }

        log.info("Daily price sync complete");
    }

    public void backfillHistoricalPrices(String symbol) {
        if (priceRepository.existsBySymbol(symbol)) {
            log.info("Prices already exist for symbol {}, skipping backfill", symbol);
            return;
        }

        log.info("Starting historical backfill for symbol {}", symbol);
        var to = LocalDate.now();
        var from = to.minusYears(2);

        var candles = priceFeedClient.getCandles(symbol, from, to);
        if (candles.isEmpty()) {
            log.warn("No candle data returned for symbol {}", symbol);
            return;
        }

        for (var entry : candles.get().entries()) {
            try {
                upsertPrice(symbol, entry.date(), entry.closePrice(), SOURCE_FINNHUB);
            } catch (Exception e) {
                log.warn("Failed to save candle for {} on {}: {}", symbol, entry.date(), e.getMessage());
            }
        }

        log.info("Historical backfill complete for symbol {}: {} entries", symbol, candles.get().entries().size());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onNewHolding(NewHoldingCreatedEvent event) {
        backfillHistoricalPrices(event.symbol());
    }

    private void upsertPrice(String symbol, LocalDate date, BigDecimal closePrice, String source) {
        var priceId = new PriceId(symbol, date);
        var existing = priceRepository.findById(priceId);

        if (existing.isPresent()) {
            var entity = existing.get();
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
