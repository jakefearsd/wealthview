package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.repository.LtcgBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Audit C8 (2026-07-12): during ACCUMULATION years the taxable pool's yield distribution is
 * never taxed (it is only ever consumed by {@link PoolStrategy.MultiPool#executeWithdrawals} in
 * a retired or RMD-forced year) -- pre-fix the growth split in {@code applyGrowth} ran every
 * single year regardless of retirement status, reinvesting the untaxed distribution as a fresh
 * AT-COST lot and thereby granting a real basis step-up a taxed investor would never get for
 * free. Post-fix, an accumulation year ({@code applyGrowth(false)}) books NO distribution at
 * all: lots grow at the full total return {@code r}, so basis is left untouched; the split (and
 * its taxation) is now a RETIRED-year-only behavior ({@code applyGrowth(true)}).
 *
 * <p>These tests directly compare the two branches ({@code applyGrowth(true)} standing in for
 * the pre-C8 "always split" behavior, {@code applyGrowth(false)} for the post-C8 fix) on an
 * IDENTICAL fixture, so the numbers below are simultaneously the fix's pin AND the regression
 * pin for what pre-C8 used to do.
 */
class MultiPoolAccumulationBasisTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int BASE_YEAR = 2025;
    private static final int AGE_ACCUMULATING = 35;
    private static final int AGE_RETIRED = 65;

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    /** ALL_US taxable account, no embedded gain at inception (basis == balance). */
    private static HypotheticalAccountInput taxableAcct(String balance, String basis) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(basis), "taxable");
    }

    private static Map<PoolType, List<ProjectionAccountInput>> grouped(HypotheticalAccountInput taxable) {
        return Map.of(
                PoolType.TAXABLE, List.of(taxable),
                PoolType.TRADITIONAL, List.of(),
                PoolType.ROTH, List.of());
    }

    /**
     * {@code interestYield} is deliberately nonzero (0.04) even though this fixture is ALL_US
     * (bond share 0, so it is a no-op for the split math) -- proves the accumulation-year no-op
     * holds even with real yield params configured, exactly as the task requires.
     */
    private static PoolStrategy.PoolConfig config(CapitalGainsTaxCalculator cg) {
        return new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST,
                null, null, Map.of(), ZERO, cg, bd("0.02"), bd("0.04"), ZERO, BASE_YEAR, null);
    }

    /** 100k taxable account, no embedded gain, 5% taxable return, 2% dividend yield. */
    private static PoolStrategy.MultiPool freshPool() {
        return new PoolStrategy.MultiPool(
                grouped(taxableAcct("100000", "100000")),
                bd("0.05"), ZERO, ZERO, bd("0.05"),
                config(capitalGainsCalc()));
    }

    // ---- (1) a single accumulation year books no distribution: no new lot, nothing to tax ----

    @Test
    void applyGrowthFalse_oneAccumulationYear_noNewLotAndFullReturnBooked() {
        var pool = freshPool();

        var g = pool.applyGrowth(false);

        assertThat(g.taxable()).isEqualByComparingTo(bd("5000"));         // full 5% growth reported
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("105000"));   // 100000 * 1.05, exactly
        assertThat(pool.taxableLotCount()).isEqualTo(1);                 // no new at-cost lot

        // A "fully covered" zero-need cycle still owes any booked dividend/RMD tax by contract
        // (see executeWithdrawals' javadoc) -- proving realizedLtcgIncome/taxLiability are BOTH
        // zero here proves nothing was booked to tax at all, not merely that it wasn't consumed.
        var r = pool.executeWithdrawals(ZERO, YEAR, bd("60000"), ZERO, ZERO, AGE_ACCUMULATING);
        assertThat(r.realizedLtcgIncome()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    // ---- (2) three accumulation years: lot count / balance vs. the pre-C8 "always split" path ----

    @Test
    void applyGrowth_threeAccumulationYears_matchesPreC8BalanceButNotPreC8LotCountOrBasis() {
        var postFix = freshPool();
        var preFix = freshPool();   // identical fixture -- pre-C8 stand-in below

        for (int i = 0; i < 3; i++) {
            postFix.applyGrowth(false);   // audit C8: accumulation year, no split
            preFix.applyGrowth(true);     // pre-C8 behavior: split ran every year regardless
        }

        // Balance-preserving in BOTH cases -- the split was already engineered to reproduce the
        // same total return; audit C8 only changes how that total is composed internally.
        assertThat(postFix.getTotal()).isEqualByComparingTo(bd("115762.5"));
        assertThat(preFix.getTotal()).isEqualByComparingTo(bd("115762.5"));

        // Lot count: post-fix never creates a distribution lot (stays at the original 1); pre-fix
        // reinvests a fresh at-cost lot every year (1 seed lot + 3 dividend lots = 4).
        assertThat(postFix.taxableLotCount()).isEqualTo(1);
        assertThat(preFix.taxableLotCount()).isEqualTo(4);
    }

    // ---- (3) first retirement-year sale: lower basis -> higher realized gain -> higher LTCG tax ----

    @Test
    void executeWithdrawals_firstRetirementSaleAfterAccumulation_higherGainAndTaxThanPreC8() {
        var postFix = freshPool();
        var preFix = freshPool();

        // TWO accumulation years, then a THIRD year that is the first RETIRED year -- deliberately
        // separate from the accumulation loop (both branches call applyGrowth(true) for it, since
        // retirement-year behavior is IDENTICAL pre/post C8) so that year's OWN legitimate dividend
        // is booked and consumed the same way in both cases, exactly like the real engine's
        // processYear (grow THEN, same year, executeWithdrawals) -- isolating the basis effect to
        // ONLY the two accumulation years' now-removed phantom credit.
        for (int i = 0; i < 2; i++) {
            postFix.applyGrowth(false);
            preFix.applyGrowth(true);
        }
        postFix.applyGrowth(true);
        preFix.applyGrowth(true);

        // Sell the ENTIRE taxable pool ($115,762.50, identical in both cases) in that retirement
        // year. Other income $60,000 already clears the single-filer 2025 0% LTCG ceiling ($48,350,
        // no FederalTaxCalculator wired here so the floor stays gross) -- the WHOLE realized gain
        // lands in the 15% bracket in both cases, isolating the basis effect exactly.
        var preR = preFix.executeWithdrawals(bd("115762.50"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);
        var postR = postFix.executeWithdrawals(bd("115762.50"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);

        // Pre-C8: basis 100000 + 2000 + 2100 (2 accumulation-year reinvested, UNTAXED dividends --
        // the phantom credit) + 2205 (the 3rd, retirement year's own dividend, taxed normally in
        // BOTH cases) = 106305 -> realized FIFO gain 115762.5 - 106305 = 9457.5, PLUS the current
        // year's own $2205 dividend = 11662.5 total LTCG income -> tax 11662.5 * 0.15 = 1749.375.
        assertThat(preR.realizedLtcgIncome()).isEqualByComparingTo(bd("11662.5"));
        assertThat(preR.taxLiability()).isEqualByComparingTo(bd("1749.375"));

        // Post-C8: basis is 100000 (untouched by the 2 accumulation years) + 2205 (the SAME
        // retirement-year dividend as pre-fix) = 102205 -> gain 115762.5 - 102205 = 13557.5, plus
        // the same $2205 dividend = 15762.5 total -> tax 15762.5 * 0.15 = 2364.375. The $615
        // tax delta (= the 2 accumulation years' $4,100 of formerly-phantom-credited dividends *
        // 15%) is EXACTLY the fix's effect, with the current year's own dividend held constant.
        assertThat(postR.realizedLtcgIncome()).isEqualByComparingTo(bd("15762.5"));
        assertThat(postR.taxLiability()).isEqualByComparingTo(bd("2364.375"));

        // Explicit direction pin: closing the untaxed basis step-up strictly increases realized
        // gain and LTCG tax on the first taxable sale after an accumulation phase.
        assertThat(postR.realizedLtcgIncome()).isGreaterThan(preR.realizedLtcgIncome());
        assertThat(postR.taxLiability()).isGreaterThan(preR.taxLiability());
    }

    // ---- (4) retirement-only horizon (no accumulation years at all) is untouched by C8 ----

    @Test
    void applyGrowthTrue_noAccumulationPhase_matchesLongstandingRetirementPin() {
        // Mirrors MultiPoolInterestYieldTest's ALL_US anchor fixture exactly (dividend_yield 0.02,
        // taxable $100k @ 6%, ROTH_FIRST draw order with a real $200k Roth balance so the $50k
        // need is drawn entirely from Roth, leaving realizedGain 0 -- isolating the dividend tax):
        // a scenario that is retired from year one never takes the accumulation-year branch, so
        // its numbers must be completely unaffected by C8.
        var rothOnlyOrderConfig = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, WithdrawalOrder.ROTH_FIRST,
                null, null, Map.of(), ZERO, capitalGainsCalc(), bd("0.02"), bd("0.10"), ZERO,
                BASE_YEAR, null);
        var pool = new PoolStrategy.MultiPool(
                Map.of(
                        PoolType.TAXABLE, List.of(taxableAcct("100000", "100000")),
                        PoolType.TRADITIONAL, List.of(),
                        PoolType.ROTH, List.of(
                                new HypotheticalAccountInput(bd("200000"), ZERO, ZERO, "roth"))),
                bd("0.06"), ZERO, ZERO, bd("0.06"),
                rothOnlyOrderConfig);

        var g = pool.applyGrowth(true);
        assertThat(g.taxable()).isEqualByComparingTo(bd("6000"));

        var r = pool.executeWithdrawals(bd("50000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);
        assertThat(r.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("300"));   // $2000 dividend @ 15%
    }
}
