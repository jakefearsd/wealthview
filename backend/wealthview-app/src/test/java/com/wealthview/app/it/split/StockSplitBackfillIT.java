package com.wealthview.app.it.split;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.wealthview.app.it.AbstractApiIntegrationTest;
import com.wealthview.app.it.AuthHelper;
import com.wealthview.app.it.testutil.QueueingSplitDetectionClient;
import com.wealthview.app.it.testutil.QueueingSplitDetectionClientConfig;
import com.wealthview.app.it.testutil.SplitTestSupport;
import com.wealthview.core.auth.TenantContext;
import com.wealthview.core.config.SystemConfigService;
import com.wealthview.core.split.StockSplitBackfillRunner;
import com.wealthview.core.split.dto.DetectedSplit;

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
@Import(QueueingSplitDetectionClientConfig.class)
class StockSplitBackfillIT extends AbstractApiIntegrationTest {

    @Autowired
    private QueueingSplitDetectionClient stubClient;

    @Autowired
    private StockSplitBackfillRunner backfillRunner;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String accountId;
    private SplitTestSupport split;

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        stubClient.reset();
        // Ensure flag is unset (cleaner already removes stock_splits.* keys
        // but explicit for the test's intent). Also wipe the SystemConfigService
        // in-memory cache so the runner re-reads from DB.
        jdbcTemplate.update("DELETE FROM system_config WHERE key LIKE 'stock_splits.%'");
        systemConfigService.set("stock_splits.backfill_completed", "false");
        jdbcTemplate.update("DELETE FROM system_config WHERE key = 'stock_splits.backfill_completed'");
        accountId = data.createBrokerageAccountAndGetId();
        split = new SplitTestSupport(api);
    }

    @Test
    void backfillRunner_appliesHistoricalSplits() {
        data.createBuyTransactionOnDateAndGetId(accountId, "2018-01-01", "AAPL", 100, 8000);
        stubClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        backfillRunner.runIfNeeded();

        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");
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

        // Quantity should still be 400, not 1600
        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");
    }

    @Test
    void backfillRunner_appliesSplits_evenWithStaleAuthenticatedContextOnCallingThread() {
        // Regression pin for hosted runs 29195289770 / 29197264627 / 29198018267 /
        // 29200037679 / 29200928162 / 29203911100: TenantFilterBackstopIT left a
        // tenant-scoped Authentication on the shared JUnit thread, and the
        // TenantFilterAspect then tenant-filtered applySplit's global reads down to
        // a long-dead tenant — "0 tenants, 0 transactions, 0 prices adjusted" while
        // the runner's own aspect-free repository scans saw the data. applySplit /
        // unapplySplit / recomputeAllForTenantAndSymbol are @CrossTenant so the
        // global split path is immune to whatever Authentication the calling
        // thread happens to carry.
        data.createBuyTransactionOnDateAndGetId(accountId, "2018-01-01", "AAPL", 100, 8000);
        stubClient.queueSplit("AAPL",
                new DetectedSplit("AAPL", LocalDate.of(2020, 8, 31), 4, 1));

        var stale = new StaleTenantPrincipal(UUID.randomUUID(), UUID.randomUUID(), "MEMBER");
        var auth = new UsernamePasswordAuthenticationToken(stale, null,
                List.of(new SimpleGrantedAuthority("ROLE_MEMBER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            backfillRunner.runIfNeeded();
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(split.holdingQuantity(accountId)).isEqualByComparingTo("400");
    }

    private record StaleTenantPrincipal(UUID userId, UUID tenantId, String role)
            implements TenantContext.AuthenticatedUser {
    }
}
