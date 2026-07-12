package com.wealthview.app.it.split;

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
 *
 * <p>The runner's {@code ContextRefreshedEvent} auto-run is disabled in the
 * "it" profile ({@code app.stock-splits.backfill-auto-run: false} in
 * application-it.yml): these tests drive
 * {@link StockSplitBackfillRunner#runIfNeeded()} explicitly, and the
 * asynchronous startup execution otherwise races the stubbed fixtures on slow
 * CI runners — on 2-core hosted runners it consumed queued splits and re-wrote
 * {@code stock_splits.backfill_completed} mid-test (hosted runs 29195289770 /
 * 29197264627 / 29198018267 / 29200037679).
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
        // Ensure flag is unset (cleaner already removes stock_splits.* keys
        // but explicit for the test's intent). Also wipe the SystemConfigService
        // in-memory cache so the runner re-reads from DB.
        jdbcTemplate.update("DELETE FROM system_config WHERE key LIKE 'stock_splits.%'");
        systemConfigService.set("stock_splits.backfill_completed", "false");
        jdbcTemplate.update("DELETE FROM system_config WHERE key = 'stock_splits.backfill_completed'");
        accountId = data.createBrokerageAccountAndGetId();
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
            var list = queued.remove(symbol);
            return list == null ? List.of() : list;
        }
    }
}
