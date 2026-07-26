package com.wealthview.app.it.split;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.app.it.testutil.QueueingSplitDetectionClient;
import com.wealthview.app.it.testutil.QueueingSplitDetectionClientConfig;
import com.wealthview.app.it.testutil.SplitTestSupport;
import com.wealthview.core.split.StockSplitBackfillRunner;
import com.wealthview.core.split.dto.DetectedSplit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sync flow coverage. Imports {@link QueueingSplitDetectionClientConfig} to
 * provide a stub {@code SplitDetectionClient} bean so the sync service runs
 * without needing a real Finnhub key (the {@code @ConditionalOnBean} guard
 * flips on).
 *
 * <p>That same conditional guard also flips on {@link StockSplitBackfillRunner},
 * whose {@code ContextRefreshedEvent} auto-run raced this class's stub client
 * on slow CI runners. The auto-run is disabled in the "it" profile
 * ({@code app.stock-splits.backfill-auto-run: false}), so the stub queue is
 * only ever touched by this class's own sync-endpoint calls.
 */
@Import(QueueingSplitDetectionClientConfig.class)
class StockSplitSyncIT extends AbstractApiIntegrationTest {

    private static final String SUPER_ADMIN_EMAIL = "split-sync-super@wealthview.test";
    private static final String SUPER_ADMIN_PASS = "superpass123";

    @Autowired
    private QueueingSplitDetectionClient stubSplitClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AuthHelper.Session superAdmin;
    private String accountId;
    private SplitTestSupport split;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        stubSplitClient.reset();
        authHelper.createSuperAdminDirectly(SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        superAdmin = authHelper.loginAsSession(restTemplate, SUPER_ADMIN_EMAIL, SUPER_ADMIN_PASS);
        accountId = data.createBrokerageAccountAndGetId();
        split = new SplitTestSupport(api);
    }

    @Test
    void scheduledSync_appliesNewSplitsFromDetectedClient() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);
        stubSplitClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        var resp = api.postForEntityAs(superAdmin.accessToken(), "/api/v1/admin/stock-splits/sync", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) resp.getBody().get("splits_applied")).isEqualTo(1);

        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");
    }

    @Test
    void scheduledSync_skipsAlreadyAppliedSplits() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);
        stubSplitClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        // First sync applies it.
        var first = api.postForEntityAs(superAdmin.accessToken(), "/api/v1/admin/stock-splits/sync", null);
        assertThat((Integer) first.getBody().get("splits_applied")).isEqualTo(1);

        // Re-queue the same split — second sync should NOT re-apply.
        stubSplitClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));
        var second = api.postForEntityAs(superAdmin.accessToken(), "/api/v1/admin/stock-splits/sync", null);
        assertThat((Integer) second.getBody().get("splits_applied")).isEqualTo(0);

        // Quantity wasn't doubled-up
        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");
    }

    @Test
    void scheduledSync_failureOnOneSymbol_continuesWithOthers() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-01", "AAPL", 100, 8000);
        data.createBuyTransactionOnDateAndGetId(accountId, "2020-01-02", "BAR", 50, 1000);
        stubSplitClient.queueFailure("AAPL");
        stubSplitClient.queueSplit("BAR",
                new DetectedSplit("BAR", LocalDate.of(2021, 1, 1), 2, 1));

        var resp = api.postForEntityAs(superAdmin.accessToken(), "/api/v1/admin/stock-splits/sync", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Integer) resp.getBody().get("splits_applied")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        var failed = (List<String>) resp.getBody().get("failed_symbols");
        assertThat(failed).contains("AAPL");
    }
}
