package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-master characterization test for {@link PoolStrategy}, whose sole implementation is
 * {@code MultiPool} (audit C11 retired the untaxed {@code SinglePool} branch: an all-taxable
 * scenario is now just a MultiPool with zero traditional and zero roth sub-pools).
 *
 * <p>{@code PoolStrategy} is fully deterministic — it contains no randomness; balances,
 * growth, contributions and withdrawals are pure arithmetic over the input accounts.
 * The same accounts and {@code PoolConfig} always produce the same numbers, so no seed
 * seam is needed.
 *
 * <p>Two fixtures pin the factory's dispatch and both shapes of the public API:
 * <ul>
 *   <li><b>all-taxable</b> — a single account type collapses to zero traditional/roth
 *       sub-pools, but is still a real, tax-aware {@code MultiPool}.</li>
 *   <li><b>mixed</b> — traditional / roth / taxable accounts kept in separate
 *       sub-pools, with taxable-first withdrawal ordering.</li>
 * </ul>
 * Every value below was produced by {@code PoolStrategy} itself and sanity-checked
 * (positive balances, withdrawals exactly equal to the requested need, growth in the
 * plausible 5-7% range, sub-pool totals reconciling to the aggregate). These assertions
 * are the behavior contract the Phase 3 decomposition of this God-class must preserve.
 */
class PoolStrategyCharacterizationTest {

    private static PoolStrategy.PoolConfig fixedConfig() {
        return new PoolStrategy.PoolConfig(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO,
                "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST, null, null);
    }

    private static PoolStrategy allTaxablePool() {
        return PoolStrategy.create(
                List.of(
                        new HypotheticalAccountInput(bd("200000"), bd("12000"), bd("0.06"), "taxable"),
                        new HypotheticalAccountInput(bd("100000"), bd("8000"), bd("0.08"), "taxable")),
                fixedConfig());
    }

    private static PoolStrategy multiPool() {
        return PoolStrategy.create(
                List.of(
                        new HypotheticalAccountInput(bd("300000"), bd("10000"), bd("0.06"), "traditional"),
                        new HypotheticalAccountInput(bd("100000"), bd("7000"), bd("0.07"), "roth"),
                        new HypotheticalAccountInput(bd("150000"), bd("5000"), bd("0.05"), "taxable")),
                fixedConfig());
    }

    @Test
    void create_allTaxableAccounts_buildsMultiPoolWithZeroTraditionalAndRoth() {
        // Audit C11: the factory no longer special-cases an all-taxable account list into a
        // separate untaxed SinglePool -- it is a real MultiPool with empty traditional/roth
        // sub-pools. Totals and weighted return are unchanged from the pre-fix numbers (both were
        // always derived the same way), but the CLASS and the downstream taxation behavior differ.
        var pool = allTaxablePool();

        assertThat(pool.getClass().getSimpleName()).isEqualTo("MultiPool");
        assertThat(pool.getTraditional()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("300000"));
        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(bd("0.06666667"));
    }

    @Test
    void allTaxablePool_applyContributionsThenGrowth_pinsBalances() {
        // Bit-identical to the pre-C11 SinglePool numbers: with dividendYield=0 and feeRate=0
        // (fixedConfig's 9-arg PoolConfig), MultiPool's taxable-lot growth is designed to reproduce
        // the scalar-balance growth exactly (see PoolStrategy.MultiPool#applyGrowth javadoc).
        var pool = allTaxablePool();

        BigDecimal contributions = pool.applyContributions();
        PoolStrategy.GrowthResult growth = pool.applyGrowth(true);

        assertThat(contributions).isEqualByComparingTo(bd("20000"));
        assertThat(growth.total()).isEqualByComparingTo(bd("21333.3344"));
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("341333.3344"));
    }

    @Test
    void allTaxablePool_executeWithdrawals_pinsRemainingBalance() {
        // No tax calculator is wired in fixedConfig, so the withdrawal is still tax-free here --
        // this test only pins the balance arithmetic, not audit C11's taxation consequence (see the
        // golden-file goldens for that).
        var pool = allTaxablePool();
        pool.applyContributions();
        pool.applyGrowth(true);

        PoolStrategy.WithdrawalTaxResult result = pool.executeWithdrawals(
                bd("50000"), 2030, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 65);

        assertThat(result.totalWithdrawn()).isEqualByComparingTo(bd("50000"));
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("291333.3344"));
    }

    @Test
    void create_mixedAccountTypes_buildsMultiPool() {
        var pool = multiPool();

        assertThat(pool.getClass().getSimpleName()).isEqualTo("MultiPool");
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("550000"));
        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(bd("0.05909091"));
    }

    @Test
    void multiPool_applyContributionsThenGrowth_pinsPerPoolGrowth() {
        var pool = multiPool();

        BigDecimal contributions = pool.applyContributions();
        PoolStrategy.GrowthResult growth = pool.applyGrowth(true);

        assertThat(contributions).isEqualByComparingTo(bd("22000"));
        // Per-pool real returns: taxable 150k@.05, traditional 300k@.06, roth 100k@.07 (inflation 0).
        assertThat(growth.total()).isEqualByComparingTo(bd("33840.0000"));
        assertThat(growth.taxable()).isEqualByComparingTo(bd("7750.0000"));       // 155000 * 0.05
        assertThat(growth.traditional()).isEqualByComparingTo(bd("18600.0000"));  // 310000 * 0.06
        assertThat(growth.roth()).isEqualByComparingTo(bd("7490.0000"));          // 107000 * 0.07
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("605840.0000"));
    }

    @Test
    void multiPool_executeWithdrawals_pinsTaxableFirstSourcing() {
        var pool = multiPool();
        pool.applyContributions();
        pool.applyGrowth(true);

        PoolStrategy.WithdrawalTaxResult result = pool.executeWithdrawals(
                bd("200000"), 2030, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 65);

        assertThat(result.totalWithdrawn()).isEqualByComparingTo(bd("200000.0000"));
        // After growth taxable = 162750; taxable-first draws it fully then 37250 from traditional.
        assertThat(result.fromTaxable()).isEqualByComparingTo(bd("162750.0000"));
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("37250.0000"));
        assertThat(result.fromRoth()).isEqualByComparingTo(bd("0.0000"));
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("405840.0000"));
    }

    @Test
    void multiPool_buildYearDto_pinsPerPoolEndingBalances() {
        var pool = multiPool();
        BigDecimal contributions = pool.applyContributions();
        PoolStrategy.GrowthResult growth = pool.applyGrowth(true);
        PoolStrategy.WithdrawalTaxResult withdrawal = pool.executeWithdrawals(
                bd("200000"), 2030, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 65);

        ProjectionYearDto dto = pool.buildYearDto(new PoolStrategy.YearDtoContext(2030, 65, bd("550000"),
                contributions, growth.total(), withdrawal.totalWithdrawn(), true,
                BigDecimal.ZERO, BigDecimal.ZERO, growth,
                withdrawal.fromTaxable(), withdrawal.fromTraditional(), withdrawal.fromRoth(),
                PoolStrategy.TaxSourceResult.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        assertThat(dto.endBalance()).isEqualByComparingTo(bd("405840.0000"));
        assertThat(dto.traditionalBalance()).isEqualByComparingTo(bd("291350.0000")); // 328600 - 37250
        assertThat(dto.rothBalance()).isEqualByComparingTo(bd("114490.0000"));        // 107000 + 7490
        assertThat(dto.taxableBalance()).isEqualByComparingTo(bd("0.0000"));
    }

    @Test
    void multiPool_perPoolEndingBalances_reconcileToAggregate() {
        // Structural invariant: the three sub-pool balances always sum to the total.
        var pool = multiPool();
        BigDecimal contributions = pool.applyContributions();
        PoolStrategy.GrowthResult growth = pool.applyGrowth(true);
        PoolStrategy.WithdrawalTaxResult withdrawal = pool.executeWithdrawals(
                bd("200000"), 2030, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 65);

        ProjectionYearDto dto = pool.buildYearDto(new PoolStrategy.YearDtoContext(2030, 65, bd("550000"),
                contributions, growth.total(), withdrawal.totalWithdrawn(), true,
                BigDecimal.ZERO, BigDecimal.ZERO, growth,
                withdrawal.fromTaxable(), withdrawal.fromTraditional(), withdrawal.fromRoth(),
                PoolStrategy.TaxSourceResult.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));

        BigDecimal sumOfPools = dto.traditionalBalance()
                .add(dto.rothBalance())
                .add(dto.taxableBalance());
        assertThat(dto.endBalance()).isEqualByComparingTo(sumOfPools);
    }
}
