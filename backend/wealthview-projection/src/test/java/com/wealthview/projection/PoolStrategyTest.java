package com.wealthview.projection;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for PoolStrategy.MultiPool.executeWithdrawals withdrawal ordering logic.
 * MultiPool is package-private, so this test lives in the same package.
 */
class PoolStrategyTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int AGE_RETIRED = 65;

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    /**
     * Constructs a MultiPool with the given per-pool balances and withdrawal order.
     * Contributions and return are zeroed out so tests are isolated to withdrawal math.
     */
    private PoolStrategy.MultiPool multiPool(String taxable, String traditional, String roth,
                                              WithdrawalOrder order) {
        List<ProjectionAccountInput> taxableAccounts = List.of(
                new HypotheticalAccountInput(bd(taxable), ZERO, ZERO, "taxable"));
        List<ProjectionAccountInput> traditionalAccounts = List.of(
                new HypotheticalAccountInput(bd(traditional), ZERO, ZERO, "traditional"));
        List<ProjectionAccountInput> rothAccounts = List.of(
                new HypotheticalAccountInput(bd(roth), ZERO, ZERO, "roth"));

        Map<String, List<ProjectionAccountInput>> grouped = Map.of(
                PoolStrategy.POOL_TAXABLE, taxableAccounts,
                PoolStrategy.POOL_TRADITIONAL, traditionalAccounts,
                PoolStrategy.POOL_ROTH, rothAccounts);

        return new PoolStrategy.MultiPool(
                grouped,
                ZERO,           // weightedReturn — no growth under test
                FilingStatus.SINGLE,
                ZERO,           // otherIncome
                ZERO,           // annualRothConversion
                "fixed",        // rothConversionStrategy
                null,           // targetBracketRate
                null,           // rothConversionStartYear
                order,
                null,           // taxCalculator — no tax in these tests
                null            // dynamicSequencingBracketRate
        );
    }

    // -------------------------------------------------------------------------
    // Test 1 — TAXABLE_FIRST: partial from taxable, remainder from traditional
    // -------------------------------------------------------------------------

    @Test
    void executeWithdrawals_taxableFirst_drawsTaxableThenTraditional() {
        var pool = multiPool("100", "200", "300", WithdrawalOrder.TAXABLE_FIRST);

        // Need $150; taxable has only $100, so $50 must come from traditional
        var result = pool.executeWithdrawals(bd("150"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(result.fromTaxable()).isEqualByComparingTo(bd("100"));
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("50"));
        assertThat(result.fromRoth()).isEqualByComparingTo(ZERO);
        assertThat(result.totalWithdrawn()).isEqualByComparingTo(bd("150"));
    }

    // -------------------------------------------------------------------------
    // Test 2 — TRADITIONAL_FIRST: all from traditional when sufficient
    // -------------------------------------------------------------------------

    @Test
    void executeWithdrawals_traditionalFirst_drawsEntirelyFromTraditional() {
        var pool = multiPool("100", "200", "300", WithdrawalOrder.TRADITIONAL_FIRST);

        // Need $150; traditional has $200, so no other pool should be touched
        var result = pool.executeWithdrawals(bd("150"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("150"));
        assertThat(result.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(result.fromRoth()).isEqualByComparingTo(ZERO);
        assertThat(result.totalWithdrawn()).isEqualByComparingTo(bd("150"));
    }

    // -------------------------------------------------------------------------
    // Test 3 — Pool exhaustion: need exceeds total, all pools drained
    // -------------------------------------------------------------------------

    @Test
    void executeWithdrawals_needExceedsTotal_drainsAllPools() {
        var pool = multiPool("100", "200", "300", WithdrawalOrder.TAXABLE_FIRST);
        BigDecimal total = bd("600");   // taxable=100 + traditional=200 + roth=300

        // Need $700 > $600 total; expect all pools to be fully drawn
        var result = pool.executeWithdrawals(bd("700"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(result.fromTaxable()).isEqualByComparingTo(bd("100"));
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("200"));
        assertThat(result.fromRoth()).isEqualByComparingTo(bd("300"));
        assertThat(result.totalWithdrawn()).isEqualByComparingTo(total);
        // End balance must be zero (nothing left)
        assertThat(pool.getTotal()).isEqualByComparingTo(ZERO);
    }

    // -------------------------------------------------------------------------
    // Test 4 — Zero need: no withdrawals made
    // -------------------------------------------------------------------------

    @Test
    void executeWithdrawals_zeroNeed_noWithdrawalsFromAnyPool() {
        var pool = multiPool("100", "200", "300", WithdrawalOrder.TAXABLE_FIRST);
        BigDecimal initialTotal = pool.getTotal();

        var result = pool.executeWithdrawals(ZERO, YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(result.totalWithdrawn()).isEqualByComparingTo(ZERO);
        assertThat(result.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(result.fromTraditional()).isEqualByComparingTo(ZERO);
        assertThat(result.fromRoth()).isEqualByComparingTo(ZERO);
        assertThat(pool.getTotal()).isEqualByComparingTo(initialTotal);
    }

    // -------------------------------------------------------------------------
    // Test 5 — Single pool: only traditional has balance, drawn regardless of order
    // -------------------------------------------------------------------------

    @Test
    void executeWithdrawals_onlyTraditionalHasBalance_drawsFromItRegardlessOfOrder() {
        // TAXABLE_FIRST order, but taxable and roth are empty
        var pool = multiPool("0", "500", "0", WithdrawalOrder.TAXABLE_FIRST);

        var result = pool.executeWithdrawals(bd("200"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("200"));
        assertThat(result.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(result.fromRoth()).isEqualByComparingTo(ZERO);
        assertThat(result.totalWithdrawn()).isEqualByComparingTo(bd("200"));
    }
}
