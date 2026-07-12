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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.split.SplitDetectionClient;
import com.wealthview.core.split.StockSplitBackfillRunner;
import com.wealthview.core.split.dto.DetectedSplit;

import static com.wealthview.app.it.testutil.TestDataHelper.LIST_MAP_TYPE;
import static com.wealthview.app.it.testutil.TestDataHelper.MAP_TYPE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync flow coverage. Uses a {@link TestConfiguration} to provide a stub
 * {@link SplitDetectionClient} bean so the sync service runs without needing
 * a real Finnhub key (the {@code @ConditionalOnBean} guard flips on).
 *
 * <p>That same conditional guard also flips on {@link StockSplitBackfillRunner}
 * (its own {@code @ConditionalOnBean(SplitDetectionClient.class)}), which fires a
 * one-shot backfill asynchronously as soon as this context refreshes and can race
 * this class's {@link StubSplitClient} the same way it does in
 * {@link StockSplitBackfillIT} — see {@link #awaitStartupAutoRunSettled()}.
 */
@Import(StockSplitSyncIT.StubSplitClientConfig.class)
class StockSplitSyncIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "split-sync-super@wealthview.test";
    private static final String SUPER_ADMIN_PASS = "superpass123";

    @Autowired
    private StubSplitClient stubSplitClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SystemConfigService systemConfigService;

    private AuthHelper.Session superAdmin;
    private String accountId;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        stubSplitClient.reset();
        awaitStartupAutoRunSettled();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        accountId = data.createBrokerageAccountAndGetId();
    }

    /**
     * See the identical guard (and full rationale) in
     * {@link StockSplitBackfillIT#awaitStartupAutoRunSettled()}: importing a
     * {@link SplitDetectionClient} bean here also satisfies
     * {@link StockSplitBackfillRunner}'s {@code @ConditionalOnBean}, so its
     * {@code ContextRefreshedEvent}-triggered auto-run can race this test's own
     * {@link StubSplitClient} under CI-runner CPU contention, silently consuming a
     * queued split before this class's own sync-endpoint calls run. Settle condition
     * matches BackfillIT's: flag "true" AND every "stock-split-backfill" thread
     * parked WAITING (executor queue drained) — waiting on the flag alone proved
     * insufficient on hosted run 29198018267.
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
    void scheduledSync_appliesNewSplitsFromDetectedClient() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);
        stubSplitClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        var resp = restTemplate.exchange("/api/v1/admin/stock-splits/sync", HttpMethod.POST,
                authHelper.authEntity(superAdmin.accessToken()), MAP_TYPE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) resp.getBody().get("splits_applied")).isEqualTo(1);

        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new java.math.BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("400");
    }

    @Test
    void scheduledSync_skipsAlreadyAppliedSplits() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);
        stubSplitClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        // First sync applies it.
        var first = restTemplate.exchange("/api/v1/admin/stock-splits/sync", HttpMethod.POST,
                authHelper.authEntity(superAdmin.accessToken()), MAP_TYPE);
        assertThat((Integer) first.getBody().get("splits_applied")).isEqualTo(1);

        // Re-queue the same split — second sync should NOT re-apply.
        stubSplitClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));
        var second = restTemplate.exchange("/api/v1/admin/stock-splits/sync", HttpMethod.POST,
                authHelper.authEntity(superAdmin.accessToken()), MAP_TYPE);
        assertThat((Integer) second.getBody().get("splits_applied")).isEqualTo(0);

        // Quantity wasn't doubled-up
        var holdings = restTemplate.exchange("/api/v1/accounts/" + accountId + "/holdings",
                HttpMethod.GET, authHelper.authEntity(authHelper.adminToken()), LIST_MAP_TYPE);
        assertThat(new java.math.BigDecimal(holdings.getBody().get(0).get("quantity").toString()))
                .isEqualByComparingTo("400");
    }

    @Test
    void scheduledSync_failureOnOneSymbol_continuesWithOthers() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-02", "BAR", 50, 1000);
        stubSplitClient.queueFailure("AAPL");
        stubSplitClient.queueSplit("BAR",
                new DetectedSplit("BAR", LocalDate.of(2021, 1, 1), 2, 1));

        var resp = restTemplate.exchange("/api/v1/admin/stock-splits/sync", HttpMethod.POST,
                authHelper.authEntity(superAdmin.accessToken()), MAP_TYPE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) resp.getBody().get("splits_applied")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var failed = (List<String>) resp.getBody().get("failed_symbols");
        assertThat(failed).contains("AAPL");
    }

    @TestConfiguration
    static class StubSplitClientConfig {
        @Bean
        public StubSplitClient stubSplitClient() {
            return new StubSplitClient();
        }

        @Bean
        public SplitDetectionClient splitDetectionClient(StubSplitClient stub) {
            return stub;
        }
    }

    static class StubSplitClient implements SplitDetectionClient {
        private final Map<String, List<DetectedSplit>> queued = new HashMap<>();
        private final java.util.Set<String> failures = new java.util.HashSet<>();

        synchronized void queueSplit(String symbol, DetectedSplit split) {
            queued.computeIfAbsent(symbol, k -> new java.util.ArrayList<>()).add(split);
        }

        synchronized void queueFailure(String symbol) {
            failures.add(symbol);
        }

        synchronized void reset() {
            queued.clear();
            failures.clear();
        }

        @Override
        public synchronized List<DetectedSplit> fetch(String symbol, LocalDate from, LocalDate to) {
            // Same defense as StockSplitBackfillIT.StubBackfillClient#fetch: queued
            // splits/failures are fixture state for this class's sync-endpoint calls
            // (which run on HTTP worker threads) — never serve or consume them on the
            // backfill runner's background startup thread. `synchronized` also gives
            // cross-thread memory visibility a bare HashMap lacks.
            if ("stock-split-backfill".equals(Thread.currentThread().getName())) {
                return List.of();
            }
            if (failures.contains(symbol)) {
                throw new RuntimeException("simulated finnhub failure");
            }
            var list = queued.remove(symbol);
            return list == null ? List.of() : list;
        }
    }
}
