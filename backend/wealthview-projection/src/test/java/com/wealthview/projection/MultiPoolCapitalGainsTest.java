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
import com.wealthview.core.projection.tax.CombinedTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.NullStateTaxCalculator;
import com.wealthview.persistence.repository.LtcgBracketRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025Ltcg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Capital-gains behavior of {@link PoolStrategy.MultiPool}: FIFO realized-gain LTCG tax and the
 * annual dividend drag. Mirrors {@link MultiPoolDeepTest} construction but wires a real
 * {@link CapitalGainsTaxCalculator} (single filer, 2025 LTCG brackets: 0% ≤ $48,350, then 15%).
 */
class MultiPoolCapitalGainsTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2025;
    private static final int BASE_YEAR = 2025;
    private static final int AGE_RETIRED = 65;

    private static CapitalGainsTaxCalculator capitalGainsCalc() {
        var repo = mock(LtcgBracketRepository.class);
        stubSingle2025Ltcg(repo);
        return new CapitalGainsTaxCalculator(repo);
    }

    /**
     * A real {@link FederalTaxCalculator} backed by single-filer 2025 fixtures ($15,000 standard
     * deduction), wired ONLY to net the LTCG stacking floor down to the same base the ordinary tax
     * would use -- {@code taxCalculator} itself stays {@code null} in these tests so {@code
     * r.taxLiability()} isolates the LTCG tax alone (no ordinary-tax component to add in).
     */
    private static FederalTaxCalculator federalTaxCalc() {
        var taxBracketRepo = mock(TaxBracketRepository.class);
        var deductionRepo = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepo, deductionRepo);
        return new FederalTaxCalculator(taxBracketRepo, deductionRepo);
    }

    /** A taxable account carrying an explicit cost basis (may be below its balance = embedded gain). */
    private static HypotheticalAccountInput taxableAcct(String balance, String basis) {
        return new HypotheticalAccountInput(bd(balance), ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(basis), "taxable");
    }

    private static HypotheticalAccountInput acct(String balance, String type) {
        return new HypotheticalAccountInput(bd(balance), ZERO, ZERO, type);
    }

    private static Map<PoolType, List<ProjectionAccountInput>> grouped(
            HypotheticalAccountInput taxable, HypotheticalAccountInput traditional,
            HypotheticalAccountInput roth) {
        return Map.of(
                PoolType.TAXABLE, List.of(taxable),
                PoolType.TRADITIONAL, List.of(traditional),
                PoolType.ROTH, List.of(roth));
    }

    /** Threads a standard-deduction source for the LTCG-floor netting fix. */
    private static PoolStrategy.PoolConfig config(String dividendYield, WithdrawalOrder order,
                                                   CapitalGainsTaxCalculator cg,
                                                   FederalTaxCalculator federalTaxCalculator) {
        return new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, order,
                null, null, Map.of(), ZERO, cg, bd(dividendYield), ZERO, ZERO, BASE_YEAR, federalTaxCalculator);
    }

    /** Per-pool returns: taxable grows at {@code taxableReturn}; traditional/roth flat. */
    private static PoolStrategy.MultiPool pool(HypotheticalAccountInput taxable,
                                                HypotheticalAccountInput traditional,
                                                HypotheticalAccountInput roth,
                                                String taxableReturn, String dividendYield,
                                                WithdrawalOrder order, CapitalGainsTaxCalculator cg) {
        return pool(taxable, traditional, roth, taxableReturn, dividendYield, order, cg, null);
    }

    /** Overload that also threads a standard-deduction source for the LTCG-floor netting fix. */
    private static PoolStrategy.MultiPool pool(HypotheticalAccountInput taxable,
                                                HypotheticalAccountInput traditional,
                                                HypotheticalAccountInput roth,
                                                String taxableReturn, String dividendYield,
                                                WithdrawalOrder order, CapitalGainsTaxCalculator cg,
                                                FederalTaxCalculator federalTaxCalculator) {
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth),
                bd(taxableReturn), ZERO, ZERO, bd(taxableReturn),
                config(dividendYield, order, cg, federalTaxCalculator));
    }

    // ---- (a) embedded-gain taxable withdrawal now pays LTCG tax (was zero) ----

    @Test
    void executeWithdrawals_taxableDrawWithEmbeddedGain_paysLtcgTaxStackedOnOrdinaryIncome() {
        // Taxable $500k with $300k basis → $200k embedded gain. Ordinary income $60k, netted by the
        // single-filer 2025 standard deduction ($15k) to the SAME $45k base the ordinary tax would
        // stack on -- pushes only part of the realized gain past the $48,350 0% LTCG ceiling.
        var pool = pool(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc(), federalTaxCalc());

        // Draw $100k taxable-first. FIFO gain = 100000 × (500000-300000)/500000 = 40000.
        // Stacking floor = 60000 − 15000 (deduction) = 45000, NOT gross 60000. Gain fills the
        // remaining $3,350 of the 0% bracket (48350 − 45000) then spills into 15%:
        //   3350 × 0% + (40000 − 3350) × 15% = 36650 × 0.15 = 5497.50
        // MAGI stays gross (100000, unaffected by the deduction) < $200k NIIT threshold → no NIIT.
        var r = pool.executeWithdrawals(bd("100000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("5497.50"));
        // No ordinary tax calculator wired in this fixture, so taxLiability is LTCG tax alone --
        // r.ltcgTax() (the field the engine folds into the federalTax breakdown) matches it exactly.
        assertThat(r.ltcgTax()).isEqualByComparingTo(bd("5497.50"));
    }

    /**
     * T23 item 2 oracle: {@code resolveOrdinaryDeduction} must net the LTCG stacking floor against
     * the CHOSEN deduction -- itemized when the year itemizes, per {@code CombinedTaxResult
     * #usedItemized()} -- not silently fall back to the standard deduction. Same $40,000
     * realized-gain / $60,000-ordinary-income fixture as the sibling test above, but wired with a
     * real {@link CombinedTaxCalculator} (SALT-capped $12,000 property tax + $25,000 mortgage
     * interest = $37,000 itemized, beating the $15,000 single standard deduction) as the ORDINARY
     * {@code taxCalculator} instead of leaving it {@code null}. The floor lands at
     * {@code 60000 - 37000 = 23000} instead of the sibling test's {@code 60000 - 15000 = 45000},
     * leaving MORE 0%-bracket headroom ({@code 48350 - 23000 = 25350} vs {@code 3350}) and therefore
     * LESS LTCG tax: {@code (40000 - 25350) * 0.15 = 2197.50} -- a concrete, oracle-verified
     * DIRECTION difference from the sibling test's $5,497.50 under the standard deduction, not
     * merely a null-vs-non-null check. A regression to the standard-deduction fallback would produce
     * $5,497.50 here instead, failing this assertion.
     */
    @Test
    void executeWithdrawals_itemizingYear_stackingFloorNetsItemizedDeductionNotStandard() {
        var federal = federalTaxCalc();
        var combinedTaxCalc = new CombinedTaxCalculator(federal, new NullStateTaxCalculator(),
                bd("12000"), bd("25000"));
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST,
                combinedTaxCalc, null, Map.of(), ZERO, capitalGainsCalc(), ZERO, ZERO, ZERO, BASE_YEAR,
                federal);
        var p = new PoolStrategy.MultiPool(
                grouped(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth")),
                ZERO, config);

        var r = p.executeWithdrawals(bd("100000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);

        assertThat(r.ltcgTax()).isEqualByComparingTo(bd("2197.50"));
    }

    @Test
    void executeWithdrawals_taxableDrawNoEmbeddedGain_noLtcgTax() {
        // Basis == balance → zero realized gain; no dividend (yield 0) → no LTCG tax at all.
        var pool = pool(taxableAcct("500000", "500000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        var r = pool.executeWithdrawals(bd("100000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
        assertThat(r.ltcgTax()).isEqualByComparingTo(ZERO);
    }

    // ---- (b) low-income retiree pays 0% on the realized gain ----

    @Test
    void executeWithdrawals_embeddedGainButLowOrdinaryIncome_gainTaxedAtZeroPercent() {
        // Same $40k realized gain, but zero ordinary income → the whole gain sits in the 0% bracket
        // ($48,350 ceiling), so no LTCG tax is owed.
        var pool = pool(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        var r = pool.executeWithdrawals(bd("100000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    // ---- (c) dividend drag reduces the taxable pool while total return is preserved ----

    @Test
    void applyGrowthThenRothDraw_dividendDragTaxesReinvestedDividend_totalReturnPreserved() {
        // Taxable $100k at 6% return, 2% dividend yield. Roth-first withdrawal draws no taxable, so
        // the ONLY LTCG income is the reinvested qualified dividend.
        var pool = pool(taxableAcct("100000", "100000"), acct("0", "traditional"), acct("200000", "roth"),
                "0.06", "0.02", WithdrawalOrder.ROTH_FIRST, capitalGainsCalc());

        // Growth splits: lots appreciate at (6% − 2%) = 4% → 104000; dividend = 106000 − 104000 = 2000
        // reinvested at cost. Total taxable still grows at exactly 6% (100000 → 106000).
        var g = pool.applyGrowth(true);
        assertThat(g.taxable()).isEqualByComparingTo(bd("6000"));   // full 6% growth reported

        // Roth-first draw: no taxable sale (realized gain 0), but the $2000 dividend is LTCG income.
        // Ordinary $60k → dividend taxed at 15% = 300 (the drag = value × yield × ltcgRate).
        var r = pool.executeWithdrawals(bd("50000"), YEAR, bd("60000"), ZERO, ZERO, AGE_RETIRED);
        assertThat(r.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("300"));

        // The dividend tax drained the taxable pool from 106000 to 105700 (only the $300 drag).
        var dto = pool.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("300000"),
                ZERO, g.total(), bd("50000"), true, ZERO, r.taxLiability(), g,
                r.fromTaxable(), r.fromTraditional(), r.fromRoth(), r.taxSource(), ZERO, ZERO, ZERO));
        assertThat(dto.taxableBalance()).isEqualByComparingTo(bd("105700"));
    }

    // ---- T18a-3: net rental income joins the NIIT Net Investment Income base ----

    @Test
    void executeWithdrawals_highIncomeRealizedGainNoRentalParam_niitOnGainAlone() {
        // Same $200k-embedded-gain taxable pool as (a) above, but high ordinary income (250000)
        // clears the $200k NIIT threshold. Draw $100k taxable-first -> FIFO gain 40000, entirely in
        // the 15% bracket (ordinary 250000 already past the 48350/533400 band) = 6000.00.
        // magi = 250000 + 40000 = 290000; excess over the 200000 threshold = 90000. Without a rental
        // figure, NII is just the 40000 gain -> niit = 40000 * 0.038 = 1520.00.
        var pool = pool(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        var r = pool.executeWithdrawals(bd("100000"), YEAR, bd("250000"), ZERO, ZERO, AGE_RETIRED,
                ZERO, ZERO, ZERO, ZERO);

        assertThat(r.taxLiability()).isEqualByComparingTo(bd("7520.00")); // 6000 bracket + 1520 NIIT
        assertThat(r.ltcgTax()).isEqualByComparingTo(r.taxLiability());
    }

    @Test
    void executeWithdrawals_highIncomeRealizedGainWithRentalIncome_niitGrowsByRentalShare() {
        // Identical fixture to the test above, but with 30000 of net taxable rental income threaded
        // through the new 10-arg executeWithdrawals overload -- rental joins the NII pot (still
        // under the 90000 magi-over-threshold excess): NII = 40000 gain + 30000 rental = 70000 ->
        // niit = 70000 * 0.038 = 2660.00. The bracket tax (6000.00) is UNCHANGED -- rental income is
        // ordinary income, not LTCG, so it never touches the 0/15/20% bracket walk.
        var pool = pool(taxableAcct("500000", "300000"), acct("0", "traditional"), acct("0", "roth"),
                "0", "0", WithdrawalOrder.TAXABLE_FIRST, capitalGainsCalc());

        var r = pool.executeWithdrawals(bd("100000"), YEAR, bd("250000"), ZERO, ZERO, AGE_RETIRED,
                ZERO, ZERO, ZERO, bd("30000"));

        assertThat(r.taxLiability()).isEqualByComparingTo(bd("8660.00")); // 6000 bracket + 2660 NIIT
        assertThat(r.ltcgTax()).isEqualByComparingTo(r.taxLiability());
        // Isolate the delta to exactly the rental-driven NIIT growth (30000 * 3.8% = 1140.00).
        assertThat(r.taxLiability().subtract(bd("7520.00"))).isEqualByComparingTo(bd("1140.00"));
    }
}
