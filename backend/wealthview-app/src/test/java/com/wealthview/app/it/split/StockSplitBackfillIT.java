package com.wealthview.app.it.split;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.split.SplitDetectionClient;
import com.wealthview.core.split.StockSplitBackfillRunner;
import com.wealthview.core.split.dto.DetectedSplit;

import static com.wealthview.app.it.testutil.TestDataHelper.LIST_MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the one-shot historical backfill runner. Uses the same
 * stub client pattern as {@link StockSplitSyncIT}.
 */
@Import(StockSplitBackfillIT.StubBackfillClientConfig.class)
class StockSplitBackfillIT extends AbstractApiIntegrationTest {

    @Autowired
    private SplitDetectionClient detectionClient;

    private StubBackfillClient stubClient;

    @Autowired
    private StockSplitBackfillRunner backfillRunner;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        stubClient = (StubBackfillClient) detectionClient;
        stubClient.reset();
        awaitStartupAutoRunSettled();
        // Ensure flag is unset (cleaner already removes stock_splits.* keys
        // but explicit for the test's intent). Also wipe the SystemConfigService
        // in-memory cache so the runner re-reads from DB.
        jdbcTemplate.update("DELETE FROM system_config WHERE key LIKE 'stock_splits.%'");
        systemConfigService.set("stock_splits.backfill_completed", "false");
        jdbcTemplate.update("DELETE FROM system_config WHERE key = 'stock_splits.backfill_completed'");
        accountId = data.createBrokerageAccountAndGetId();
    }

    /**
     * {@link StockSplitBackfillRunner} auto-runs once, asynchronously on a background
     * "stock-split-backfill" thread, via its {@code ContextRefreshedEvent} listener as
     * soon as this test class's Spring context comes up (the
     * {@code @ConditionalOnBean(SplitDetectionClient)} guard is satisfied because
     * {@link StubBackfillClientConfig} supplies one). On a fast machine that auto-run
     * always finishes before this method runs. Under CPU contention (shared 2-core CI
     * runners: hosted runs 29195289770, 29197264627, 29198018267) its execution can be
     * delayed into or across the test bodies, where it silently consumes the stub's
     * queued split and/or re-writes {@code stock_splits.backfill_completed=true} into
     * {@link SystemConfigService}'s in-memory cache after this setUp's reset — making the
     * test's own {@code runIfNeeded()} call a no-op (holdings stay at pre-split quantity).
     *
     * <p>Waiting on the flag alone proved insufficient on CI (run 29198018267): the flag
     * shows that one execution completed, not that the runner's single-thread executor is
     * drained. The settle condition here is therefore: flag reads "true" AND at least one
     * "stock-split-backfill" thread exists AND every thread with that name is parked in
     * {@code WAITING} — i.e. blocked on the idle executor queue's take(), so the queue is
     * empty and nothing is in flight. Cached sibling contexts (e.g.
     * {@link StockSplitSyncIT}'s) keep identically-named parked threads alive, hence the
     * for-all quantifier. ContextRefreshedEvents only occur during startup, so once
     * drained the runner stays drained. Bounded and non-throwing: on timeout this falls
     * through and the reset below runs as it always did.
     */
    private void awaitStartupAutoRunSettled() {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            if ("true".equalsIgnoreCase(systemConfigService.get("stock_splits.backfill_completed"))
                    && backfillExecutorDrained()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean backfillExecutorDrained() {
        var backfillThreads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> "stock-split-backfill".equals(t.getName()))
                .toList();
        return !backfillThreads.isEmpty()
                && backfillThreads.stream().allMatch(t -> t.getState() == Thread.State.WAITING);
    }

    @Test
    void backfillRunner_appliesHistoricalSplits() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2018-01-01", "AAPL", 100, 8000);
        stubClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        backfillRunner.runIfNeeded();

        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new java.math.BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("400");
        assertThat(systemConfigService.get("stock_splits.backfill_completed")).isEqualTo("true");
    }

    @Test
    void backfillRunner_idempotent_secondRunDoesNothing() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2018-01-01", "AAPL", 100, 8000);
        stubClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        backfillRunner.runIfNeeded();

        // Re-queue split to verify it would be picked up if the runner re-ran.
        stubClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        backfillRunner.runIfNeeded();

        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        // Quantity should still be 400, not 1600
        assertThat(new java.math.BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("400");
    }

    @TestConfiguration
    static class StubBackfillClientConfig {
        @Bean
        public SplitDetectionClient splitDetectionClient() {
            return new StubBackfillClient();
        }
    }

    static class StubBackfillClient implements SplitDetectionClient {
        private final Map<String, List<DetectedSplit>> queued = new HashMap<>();

        synchronized void queueSplit(String symbol, DetectedSplit split) {
            queued.computeIfAbsent(symbol, k -> new java.util.ArrayList<>()).add(split);
        }

        synchronized void reset() {
            queued.clear();
        }

        @Override
        public synchronized List<DetectedSplit> fetch(String symbol, LocalDate from, LocalDate to) {
            // Defense in depth against the startup auto-run race documented on
            // awaitStartupAutoRunSettled(): the runner's ContextRefreshedEvent
            // auto-run always executes on its dedicated "stock-split-backfill"
            // thread, while the tests invoke runIfNeeded() directly on the JUnit
            // thread. Splits queued by a test are fixture state for that test's
            // own call — never hand them to the background thread, and never let
            // it consume (remove) them either. `synchronized` also gives the
            // cross-thread memory visibility a bare HashMap lacks.
            if ("stock-split-backfill".equals(Thread.currentThread().getName())) {
                return List.of();
            }
            var list = queued.remove(symbol);
            return list == null ? List.of() : list;
        }
    }
}
