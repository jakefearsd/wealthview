package com.wealthview.core.split;

import java.time.LocalDate;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.stereotype.Component;

import com.wealthview.core.config.SystemConfigService;
import com.wealthview.persistence.repository.StockSplitRepository;
import com.wealthview.persistence.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * One-time backfill: on first deployment after this feature ships, scan every
 * distinct symbol the app has ever seen and apply historical splits since
 * each symbol's earliest transaction date. Idempotent — guarded by the
 * {@code stock_splits.backfill_completed} flag in {@code system_config}.
 *
 * <p>Runs asynchronously after {@link ContextRefreshedEvent} so it never
 * blocks app startup. May take a minute or two against Finnhub depending on
 * portfolio breadth; progress is logged at INFO.
 */
@Component
@ConditionalOnBean(SplitDetectionClient.class)
public class StockSplitBackfillRunner {

    private static final Logger log = LoggerFactory.getLogger(StockSplitBackfillRunner.class);
    private static final String BACKFILL_FLAG = "stock_splits.backfill_completed";

    private final SplitDetectionClient splitDetectionClient;
    private final StockSplitService stockSplitService;
    private final StockSplitRepository stockSplitRepository;
    private final TransactionRepository transactionRepository;
    private final SystemConfigService systemConfigService;
    private final MeterRegistry meterRegistry;
    private final TaskExecutor taskExecutor;

    public StockSplitBackfillRunner(SplitDetectionClient splitDetectionClient,
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
        // Use a dedicated single-thread executor so the backfill cannot starve
        // the @Async pool the app uses for unrelated work.
        this.taskExecutor = new TaskExecutorAdapter(Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "stock-split-backfill");
            t.setDaemon(true);
            return t;
        }));
    }

    @EventListener(ContextRefreshedEvent.class)
    public void onApplicationReady() {
        taskExecutor.execute(this::runIfNeeded);
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void runIfNeeded() {
        if ("true".equalsIgnoreCase(systemConfigService.get(BACKFILL_FLAG))) {
            return;
        }
        log.info("Stock split backfill starting (first run)");
        var symbols = transactionRepository.findDistinctSymbolsAcrossAllTenants();
        int totalApplied = 0;
        for (var symbol : symbols) {
            try {
                var earliest = transactionRepository.findEarliestDateBySymbol(symbol);
                if (earliest.isEmpty()) {
                    continue;
                }
                var from = earliest.orElseThrow();
                var to = LocalDate.now();
                var splits = splitDetectionClient.fetch(symbol, from, to);
                for (var s : splits) {
                    if (!stockSplitRepository.existsBySymbolAndEffectiveDate(symbol, s.date())) {
                        stockSplitService.applySplit(symbol, s.date(), s.numerator(), s.denominator(), "backfill");
                        totalApplied++;
                    }
                }
            } catch (Exception e) {
                log.warn("Backfill failed for {}: {}", symbol, e.getMessage());
            }
        }

        systemConfigService.set(BACKFILL_FLAG, "true");
        meterRegistry.counter("wealthview.splits.backfill_completed_total").increment();
        log.info("Stock split backfill complete: {} symbols scanned, {} splits applied",
                symbols.size(), totalApplied);
    }
}
