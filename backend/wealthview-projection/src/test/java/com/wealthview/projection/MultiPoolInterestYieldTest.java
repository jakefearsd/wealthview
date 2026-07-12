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
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalOnlyTaxStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Audit C1 (2026-07-12): the taxable pool's annual yield is split by the account's OWN asset
 * allocation instead of applying {@code dividendYield} to the whole balance -- the equity share
 * (us_stock + intl_stock) keeps the qualified-dividend/LTCG treatment; the bond+cash share is
 * taxed as ORDINARY income at {@code interestYield} instead. Mirrors {@link
 * MultiPoolCapitalGainsTest}'s construction and fixture values so the "before" (ALL_US, 100%
 * equity share) and "after" (60/40) numbers are directly comparable.
 */
class MultiPoolInterestYieldTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int BASE_YEAR = 2025;
    private static final int AGE_RETIRED = 65;

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    private static FederalTaxCalculator federalTaxCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    /** A taxable account carrying a 60% us_stock / 40% bond allocation, no return override. */
    private static HypotheticalAccountInput bondAllocatedTaxableAcct(String balance, String basis) {
        var allocation = new AssetAllocation(Map.of(
                AssetClass.US_STOCK, bd("0.6"), AssetClass.BOND, bd("0.4")));
        return new HypotheticalAccountInput(bd(balance), ZERO, allocation, Optional.empty(), bd(basis), "taxable");
    }

    /** The pre-C1 anchor shape: ALL_US allocation (equity share 1.0, bond share 0.0). */
    private static HypotheticalAccountInput allUsTaxableAcct(String balance, String basis) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US, Optional.empty(),
                bd(basis), "taxable");
    }

    private static HypotheticalAccountInput acct(String balance, String type) {
        return new HypotheticalAccountInput(bd(balance), ZERO, ZERO, type);
    }

    private static Map<String, List<ProjectionAccountInput>> grouped(
            HypotheticalAccountInput taxable, HypotheticalAccountInput traditional,
            HypotheticalAccountInput roth) {
        return Map.of(
                PoolStrategy.POOL_TAXABLE, List.of(taxable),
                PoolStrategy.POOL_TRADITIONAL, List.of(traditional),
                PoolStrategy.POOL_ROTH, List.of(roth));
    }

    private static PoolStrategy.PoolConfig config(String dividendYield, String interestYield,
                                                   CapitalGainsTaxCalculator cg,
                                                   FederalTaxCalculator federalTaxCalculator,
                                                   com.wealthview.core.projection.tax.TaxCalculationStrategy taxCalculator) {
        return new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, WithdrawalOrder.ROTH_FIRST,
                taxCalculator, null, Map.of(), ZERO, cg, bd(dividendYield), bd(interestYield), ZERO,
                BASE_YEAR, federalTaxCalculator);
    }

    private static PoolStrategy.MultiPool pool(HypotheticalAccountInput taxable, String taxableReturn,
                                                String dividendYield, String interestYield,
                                                CapitalGainsTaxCalculator cg,
                                                FederalTaxCalculator federalTaxCalculator,
                                                com.wealthview.core.projection.tax.TaxCalculationStrategy taxCalculator) {
        return new PoolStrategy.MultiPool(
                grouped(taxable, acct("0", "traditional"), acct("200000", "roth")),
                bd(taxableReturn), ZERO, ZERO, bd(taxableReturn),
                config(dividendYield, interestYield, cg, federalTaxCalculator, taxCalculator));
    }

    // ---- ALL_US backward-compat anchor: interest_yield must be a complete no-op ----

    @Test
    void applyGrowthThenRothDraw_allUsAllocation_interestYieldIgnored_byteIdenticalToPreC1() {
        // Same fixture as MultiPoolCapitalGainsTest's dividend-drag pin, but with a large
        // interest_yield (0.10) that must have ZERO effect: ALL_US -> equityShare=1, bondShare=0.
        var pool = pool(allUsTaxableAcct("100000", "100000"), "0.06", "0.02", "0.10",
                capitalGainsCalc(), null, null);

        var g = pool.applyGrowth(true);
        assertThat(g.taxable()).isEqualByComparingTo(bd("6000"));   // full 6% growth reported

        var r = pool.executeWithdrawals(bd("50000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);
        assertThat(r.fromTaxable()).isEqualByComparingTo(ZERO);
        // Bit-identical to the pre-C1 pin: $2000 dividend (100% of the yield) taxed at 15% = $300.
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("300"));

        var dto = pool.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("300000"),
                ZERO, g.total(), bd("50000"), true, ZERO, r.taxLiability(), g,
                r.fromTaxable(), r.fromTraditional(), r.fromRoth(), r.taxSource(), ZERO, ZERO, ZERO));
        assertThat(dto.taxableBalance()).isEqualByComparingTo(bd("105700"));
    }

    // ---- 60/40 split reduces qualified-dividend/LTCG income vs the ALL_US pin above ----

    @Test
    void applyGrowthThenRothDraw_bondAllocatedAccount_reducesQualifiedDividendIncomeVsAllUs() {
        // Identical fixture to the ALL_US anchor test above (100k @ 6%, dividend_yield 0.02) except
        // the account carries a 60/40 us_stock/bond allocation and interest_yield 0.04. Growth
        // splits: blendedYield = 0.6*0.02 + 0.4*0.04 = 0.028; lots grow at (0.06-0.028)=0.032 ->
        // 103200; total distribution = 106000 - 103200 = 2800, split proportionally to each
        // sleeve's yield contribution: dividend = 2800 * (0.012/0.028) = 1200 (= 0.6 * 100000 *
        // 0.02 -- the equity share ALONE, not the whole balance); interest = 2800 - 1200 = 1600
        // (= 0.4 * 100000 * 0.04). The dividend fell from $2000 (ALL_US) to $1200 -- audit C1's
        // reduction. LTCG tax on $1200 @ 15% = $180 (was $300 for the full $2000 under ALL_US).
        var pool = pool(bondAllocatedTaxableAcct("100000", "100000"), "0.06", "0.02", "0.04",
                capitalGainsCalc(), null, null);

        var g = pool.applyGrowth(true);
        assertThat(g.taxable()).isEqualByComparingTo(bd("6000"));   // total return still exactly 6%

        var r = pool.executeWithdrawals(bd("50000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);
        assertThat(r.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("180"));

        // Taxable balance drains by exactly the $180 LTCG drag: 106000 - 180 = 105820 (vs 105700
        // under ALL_US -- less tax leaves the pool because less income was qualified-dividend-taxed
        // this year; the untaxed interest portion is still reinvested in the pool either way).
        var dto = pool.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("300000"),
                ZERO, g.total(), bd("50000"), true, ZERO, r.taxLiability(), g,
                r.fromTaxable(), r.fromTraditional(), r.fromRoth(), r.taxSource(), ZERO, ZERO, ZERO));
        assertThat(dto.taxableBalance()).isEqualByComparingTo(bd("105820"));
    }

    // ---- ordinary interest income joins the ordinary tax bundle, not the LTCG bundle ----

    @Test
    void executeWithdrawals_bondAllocatedAccount_taxesInterestAsOrdinaryIncomeNotLtcg() {
        // Real ordinary FederalTaxCalculator wired, NO capital-gains calculator -- isolates the
        // ordinary-tax bundle exactly like MultiPoolCapitalGainsTest's LTCG tests isolate the LTCG
        // bundle by omitting the ordinary one. Zero growth (taxableReturn 0) so the ONLY income
        // this year is the yield split: dividend = 100000*0.6*0.018 = 1080 (untaxed here -- no LTCG
        // calculator), interest = 100000*0.4*0.04 = 1600 (taxed ORDINARY, stacked on top of the
        // $40,000 base other-income). No alreadyChargedBaseTax is threaded through this direct
        // MultiPool-level call, so executeWithdrawals' single-shot ordinary bundle IS the full tax
        // on the whole taxableIncome (effectiveOtherIncome + ordinaryInterestIncome), not a delta
        // -- pin it directly against the oracle, then prove the interest's OWN marginal share by an
        // A/B comparison against an identical pool with interest_yield = 0.
        var federalCalc = federalTaxCalc();
        var taxStrategy = new FederalOnlyTaxStrategy(federalCalc);
        var poolWithInterest = pool(bondAllocatedTaxableAcct("100000", "100000"), "0", "0.018", "0.04",
                null, null, taxStrategy);
        var poolNoInterest = pool(bondAllocatedTaxableAcct("100000", "100000"), "0", "0.018", "0",
                null, null, taxStrategy);
        poolWithInterest.applyGrowth(true);
        poolNoInterest.applyGrowth(true);

        var rWithInterest = poolWithInterest.executeWithdrawals(ZERO, YEAR, bd("40000"), ZERO, ZERO, AGE_RETIRED);
        var rNoInterest = poolNoInterest.executeWithdrawals(ZERO, YEAR, bd("40000"), ZERO, ZERO, AGE_RETIRED);

        // Oracle: single-filer 2025 fixtures ($15,000 standard deduction, 10% to $11,925, 12%
        // above) -- taxAt(41600) = 2,953.50; taxAt(40000) = 2,761.50. Both 25000 and 26600 taxable
        // bases sit fully in the 12% bracket, so the interest's marginal tax is exactly
        // 1600 * 0.12 = 192.00.
        assertThat(rWithInterest.taxLiability())
                .isEqualByComparingTo(federalCalc.computeTax(bd("41600"), YEAR, FilingStatus.SINGLE));
        assertThat(rNoInterest.taxLiability())
                .isEqualByComparingTo(federalCalc.computeTax(bd("40000"), YEAR, FilingStatus.SINGLE));
        assertThat(rWithInterest.taxLiability().subtract(rNoInterest.taxLiability()))
                .isEqualByComparingTo(bd("192.00"));
        assertThat(rWithInterest.ltcgTax()).isEqualByComparingTo(ZERO);
    }
}
