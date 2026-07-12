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
     * thread, via its {@code ContextRefreshedEvent} listener as soon as this test
     * class's Spring context comes up (the {@code @ConditionalOnBean(SplitDetectionClient)}
     * guard is satisfied because {@link StubBackfillClientConfig} supplies one). On a fast
     * machine that auto-run always finishes before this method runs. Under CPU contention
     * (e.g. a shared 2-core CI runner) scheduling delays can push its completion into the
     * window where the test body below is queuing its own split — letting that unrelated
     * auto-run silently consume it and mark the backfill "completed" in
     * {@link SystemConfigService}'s in-memory cache (which the raw-JDBC deletes above don't
     * touch) before this test's own explicit {@code runIfNeeded()} call ever gets a chance
     * to run. That is a genuine race, not a slow-assertion issue: waiting longer after the
     * fact would not help, because by the time it manifests the split has already been
     * silently dropped. Block here, once, on the actual settle condition (not a fixed
     * sleep) until that startup auto-run has visibly finished, so every step below is
     * guaranteed to happen strictly after it rather than racing it. Bounded and
     * non-throwing: if the auto-run never settles (e.g. an unrelated startup failure) this
     * simply falls through and the reset below runs as it always did, so behavior can only
     * improve relative to before this guard existed.
     */
    private void awaitStartupAutoRunSettled() {
        var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!"true".equalsIgnoreCase(systemConfigService.get("stock_splits.backfill_completed"))
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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

        void queueSplit(String symbol, DetectedSplit split) {
            queued.computeIfAbsent(symbol, k -> new java.util.ArrayList<>()).add(split);
        }

        void reset() {
            queued.clear();
        }

        @Override
        public List<DetectedSplit> fetch(String symbol, LocalDate from, LocalDate to) {
            var list = queued.remove(symbol);
            return list == null ? List.of() : list;
        }
    }
}
