package com.wealthview.core.split;

import java.time.LocalDate;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.stereotype.Component;

import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.split.dto.DetectedSplit;
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
 *
 * <p>The startup auto-run is gated by {@code app.stock-splits.backfill-auto-run}
 * (default {@code true}, so production behavior is unchanged when the property
 * is absent). Integration tests set it to {@code false}: they drive
 * {@link #runIfNeeded()} explicitly, and the asynchronous startup execution
 * otherwise races their fixtures on slow CI runners (it consumes stubbed
 * splits and re-writes the completed flag mid-test).
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
    private final boolean backfillAutoRun;

    public StockSplitBackfillRunner(SplitDetectionClient splitDetectionClient,
                                    StockSplitService stockSplitService,
                                    StockSplitRepository stockSplitRepository,
                                    TransactionRepository transactionRepository,
                                    SystemConfigService systemConfigService,
                                    MeterRegistry meterRegistry,
                                    @Value("${app.stock-splits.backfill-auto-run:true}")
                                    boolean backfillAutoRun) {
        this.splitDetectionClient = splitDetectionClient;
        this.stockSplitService = stockSplitService;
        this.stockSplitRepository = stockSplitRepository;
        this.transactionRepository = transactionRepository;
        this.systemConfigService = systemConfigService;
        this.meterRegistry = meterRegistry;
        this.backfillAutoRun = backfillAutoRun;
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
        if (!backfillAutoRun) {
            log.info("Stock split backfill auto-run disabled via app.stock-splits.backfill-auto-run");
            return;
        }
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
                        warnIfSplitAffectedNoTenants(symbol, s);
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

    /**
     * {@code symbol} reached this point via {@link TransactionRepository#findDistinctSymbolsAcrossAllTenants()}
     * moments earlier in this same run, so by definition at least one tenant held it then. If
     * {@link StockSplitService#applySplit} reports zero affected tenants for that exact symbol
     * immediately afterward, the two reads disagree about the same committed data — unlike an
     * admin-driven apply for a symbol nobody currently holds (a legitimate, documented case),
     * that is never expected for a backfill-discovered split. The split row is still recorded
     * (so a future run's {@code existsBySymbolAndEffectiveDate} check will not re-detect it),
     * but holdings for the symbol may be un-adjusted; surface that loudly instead of letting it
     * vanish silently into "splits applied".
     */
    private void warnIfSplitAffectedNoTenants(String symbol, DetectedSplit split) {
        if (transactionRepository.findDistinctTenantIdsBySymbol(symbol).isEmpty()) {
            log.warn("Backfill applied split {}:{} for {} on {} but found ZERO tenants holding {} "
                    + "immediately afterward, despite {} being discovered by this run's own "
                    + "distinct-symbol scan — holdings may not be split-adjusted; investigate before "
                    + "trusting balances for this symbol",
                    split.numerator(), split.denominator(), symbol, split.date(), symbol, symbol);
        }
    }
}
