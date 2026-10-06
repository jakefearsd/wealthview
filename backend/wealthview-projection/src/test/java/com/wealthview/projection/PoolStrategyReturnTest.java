package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

class PoolStrategyReturnTest {

    private static final Map<AssetClass, Double> GEO = Map.of(
            AssetClass.US_STOCK, 0.07, AssetClass.INTL_STOCK, 0.06,
            AssetClass.BOND, 0.02, AssetClass.CASH, 0.005);

    @Test
    void realReturnFor_allocation_blendsGeometricMeans() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.5, AssetClass.BOND, 0.5)),
                Optional.empty(), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"), BigDecimal.ZERO);

        assertThat(r.doubleValue()).isCloseTo(0.045, org.assertj.core.data.Offset.offset(1e-6)); // .5*.07+.5*.02
    }

    @Test
    void realReturnFor_override_convertsNominalToReal() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.of(new BigDecimal("0.07")), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"), BigDecimal.ZERO);

        // (1.07/1.025)-1 = 0.043902...
        assertThat(r.doubleValue()).isCloseTo(0.0439024, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void realReturnFor_overrideZeroInflation_reproducesNominal() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.of(new BigDecimal("0.06")), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, BigDecimal.ZERO, BigDecimal.ZERO);

        assertThat(r).isEqualByComparingTo(new BigDecimal("0.06"));
    }

    // B1 (2026-07-11 audit): the scenario's fee rate must be subtracted from the real return
    // uniformly across BOTH the allocation-blend path and the fixed-override path — a fee is a fee.

    @Test
    void realReturnFor_allocation_subtractsFeeRate() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.fromDoubles(Map.of(AssetClass.US_STOCK, 0.5, AssetClass.BOND, 0.5)),
                Optional.empty(), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"), new BigDecimal("0.0025"));

        // .5*.07+.5*.02 - .0025 = .045 - .0025 = .0425 -- blended real return reduced by exactly the fee.
        assertThat(r.doubleValue()).isCloseTo(0.0425, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void realReturnFor_override_subtractsFeeRate() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.of(new BigDecimal("0.07")), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"), new BigDecimal("0.0025"));

        // (1.07/1.025)-1-0.0025 = 0.043902439... - 0.0025 = 0.041402439...
        assertThat(r.doubleValue()).isCloseTo(0.0414024390, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void realReturnFor_feeRateZero_matchesPreFeeBehavior() {
        ProjectionAccountInput acct = new HypotheticalAccountInput(
                new BigDecimal("1000"), BigDecimal.ZERO,
                AssetAllocation.ALL_US, Optional.of(new BigDecimal("0.07")), "taxable");

        BigDecimal r = PoolStrategy.realReturnFor(acct, GEO, new BigDecimal("0.025"), BigDecimal.ZERO);

        assertThat(r.doubleValue()).isCloseTo(0.0439024390, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void multiPool_perPoolGrowth_usesEachTypesOwnReturn() {
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO,
                "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST, null, null, GEO, BigDecimal.ZERO);
        // Each account overrides with its own nominal return; zero inflation ⇒ real == nominal.
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.05"), "taxable"),
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.06"), "traditional"),
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.07"), "roth")),
                config);

        var growth = pool.applyGrowth(true);

        assertThat(growth.taxable()).isEqualByComparingTo(bd("5000"));       // 100000 * 0.05
        assertThat(growth.traditional()).isEqualByComparingTo(bd("6000"));   // 100000 * 0.06
        assertThat(growth.roth()).isEqualByComparingTo(bd("7000"));          // 100000 * 0.07
    }

    // B1: same three-pool fixture as above, but wired through the FULL PoolConfig constructor with
    // a nonzero feeRate -- the back-compat 9-arg constructor used above always resolves feeRate to
    // ZERO (legacy callers stay fee-free), so this test deliberately exercises the canonical ctor.
    @Test
    void multiPool_perPoolGrowth_subtractsFeeRateUniformlyAcrossPools() {
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO,
                "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST, null, null, GEO, BigDecimal.ZERO,
                null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.0025"), 2025, null);
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.05"), "taxable"),
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.06"), "traditional"),
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.07"), "roth")),
                config);

        var growth = pool.applyGrowth(true);

        assertThat(growth.taxable()).isEqualByComparingTo(bd("4750"));       // 100000 * (0.05 - 0.0025)
        assertThat(growth.traditional()).isEqualByComparingTo(bd("5750"));   // 100000 * (0.06 - 0.0025)
        assertThat(growth.roth()).isEqualByComparingTo(bd("6750"));          // 100000 * (0.07 - 0.0025)
    }

    // API #23 / #5: a pool whose accounts all open at $0 (a brand-new account, or the Roth pool a
    // conversion creates) used to be weighted by a zero balance and grow at 0% for the whole run.

    private static PoolStrategy.PoolConfig zeroInflationConfig() {
        return new PoolStrategy.PoolConfig(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO,
                "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST, null, null, GEO, BigDecimal.ZERO);
    }

    @Test
    void multiPool_zeroOpeningPoolWithContributions_growsAtItsOwnAccountsReturn() {
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.03"), "traditional"),
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("7000"), bd("0.07"), "roth")),
                zeroInflationConfig());

        pool.applyContributions();
        var growth = pool.applyGrowth(false);

        assertThat(growth.roth()).isEqualByComparingTo(bd("490"));            // 7000 * 0.07
    }

    @Test
    void multiPool_zeroOpeningPool_weightsAccountsByContribution() {
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("3000"), bd("0.05"), "roth"),
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("1000"), bd("0.09"), "roth")),
                zeroInflationConfig());

        pool.applyContributions();
        var growth = pool.applyGrowth(false);

        // (3000*0.05 + 1000*0.09) / 4000 = 0.06 on the 4000 contributed.
        assertThat(growth.roth()).isEqualByComparingTo(bd("240"));
    }

    @Test
    void create_allBalancesAndContributionsZero_weightedReturnIsEqualWeightMean() {
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(BigDecimal.ZERO, BigDecimal.ZERO, bd("0.05"), "taxable"),
                new HypotheticalAccountInput(BigDecimal.ZERO, BigDecimal.ZERO, bd("0.07"), "roth")),
                zeroInflationConfig());

        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(bd("0.06"));
    }

    @Test
    void create_zeroBalancesWithContributionsSummingToZero_doesNotDivideByZero() {
        // A legacy negative contribution (a planned pre-retirement withdrawal) can cancel a positive
        // one. Weighting by the raw contributions summed the weights to zero and threw on the divide.
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("10000"), bd("0.05"), "roth"),
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("-10000"), bd("0.09"), "roth")),
                zeroInflationConfig());

        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(bd("0.05"));
    }

    @Test
    void create_zeroBalancesWithANegativeContribution_givesItNoWeight() {
        // Raw weights 10000 / -5000 extrapolated the blend to 2*0.05 - 0.09 = 0.01.
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("10000"), bd("0.05"), "roth"),
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("-5000"), bd("0.09"), "roth")),
                zeroInflationConfig());

        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(bd("0.05"));
    }

    @Test
    void create_zeroBalancesAndOnlyNegativeContributions_weightedReturnIsEqualWeightMean() {
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("-1000"), bd("0.05"), "taxable"),
                new HypotheticalAccountInput(BigDecimal.ZERO, bd("-3000"), bd("0.07"), "roth")),
                zeroInflationConfig());

        assertThat(pool.getWeightedReturn()).isEqualByComparingTo(bd("0.06"));
    }

    @Test
    void multiPool_poolWithNoAccounts_growsAtHouseholdBalanceWeightedReturn() {
        // No taxable account: money that lands in the taxable pool later (RMD excess, surplus
        // reinvestment) grows at the household's overall return, (300k*0.04 + 100k*0.08)/400k = 0.05.
        var pool = PoolStrategy.create(List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("300000"), BigDecimal.ZERO, bd("0.04"), "traditional"),
                new HypotheticalAccountInput(bd("100000"), BigDecimal.ZERO, bd("0.08"), "roth")),
                zeroInflationConfig());

        pool.depositToTaxable(bd("10000"));
        var growth = pool.applyGrowth(false);

        assertThat(growth.taxable()).isEqualByComparingTo(bd("500"));          // 10000 * 0.05
    }
}
