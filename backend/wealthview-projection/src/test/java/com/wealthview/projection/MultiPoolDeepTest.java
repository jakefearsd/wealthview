package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FederalOnlyTaxStrategy;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Deep coverage of PoolStrategy.MultiPool — Roth conversions, dynamic sequencing,
 * pro-rata withdrawals, tax-aware withdrawal math, and buildYearDto's tax-detail path.
 */
class MultiPoolDeepTest {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int YEAR = 2030;
    private static final int AGE_EARLY = 55;
    private static final int AGE_RETIRED = 65;

    // ---- helpers ----

    private static Map<String, List<ProjectionAccountInput>> grouped(
            String taxable, String traditional, String roth,
            String taxableContrib, String traditionalContrib, String rothContrib) {
        return Map.of(
                PoolStrategy.POOL_TAXABLE,
                List.of(new HypotheticalAccountInput(bd(taxable), bd(taxableContrib), ZERO, "taxable")),
                PoolStrategy.POOL_TRADITIONAL,
                List.of(new HypotheticalAccountInput(bd(traditional), bd(traditionalContrib), ZERO, "traditional")),
                PoolStrategy.POOL_ROTH,
                List.of(new HypotheticalAccountInput(bd(roth), bd(rothContrib), ZERO, "roth")));
    }

    private PoolStrategy.MultiPool pool(String taxable, String traditional, String roth,
                                         WithdrawalOrder order) {
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                order, null, null);
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth, "0", "0", "0"),
                ZERO, config);
    }

    private PoolStrategy.MultiPool poolWithConversion(String taxable, String traditional, String roth,
                                                        String annualConv, String rothStrategy,
                                                        String targetRate, Integer startYear,
                                                        TaxCalculationStrategy taxCalc) {
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, bd(annualConv), rothStrategy,
                targetRate != null ? bd(targetRate) : null,
                startYear, WithdrawalOrder.TAXABLE_FIRST, taxCalc, null);
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth, "0", "0", "0"),
                ZERO, config);
    }

    /**
     * Builds a MultiPool with a caller-chosen withdrawal order AND tax strategy (no conversion) --
     * {@link #poolWithConversion} hardcodes taxable-first, which isn't always what a tax-payment
     * gross-up test needs (audit C2: the spend draw's own ordering matters for isolating "does the
     * TAX payment touch traditional" from "does the SPEND draw touch traditional").
     */
    private PoolStrategy.MultiPool poolWithOrderAndTax(String taxable, String traditional, String roth,
                                                         WithdrawalOrder order, TaxCalculationStrategy taxCalc) {
        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                order, taxCalc, null);
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth, "0", "0", "0"),
                ZERO, config);
    }

    /**
     * Builds a MultiPool wired with a real FederalTaxCalculator (single filer, 2025 bracket
     * fixtures) rather than a flat-rate fake, for tests that need genuine progressive tax math.
     */
    private PoolStrategy.MultiPool poolWithRealTax(String taxable, String traditional, String roth,
                                                     WithdrawalOrder order) {
        var taxBracketRepository = mock(TaxBracketRepository.class);
        var standardDeductionRepository = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        TaxCalculationStrategy realTaxCalc = new FederalOnlyTaxStrategy(
                new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository));

        var config = new PoolStrategy.PoolConfig(
                FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                order, realTaxCalc, null);
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth, "0", "0", "0"),
                ZERO, config);
    }

    private static TaxCalculationStrategy flatTaxCalc(String rate) {
        BigDecimal r = bd(rate);
        return new TaxCalculationStrategy() {
            @Override
            public BigDecimal computeTotalTax(BigDecimal gross, int yr, FilingStatus fs) {
                return gross.multiply(r);
            }

            @Override
            public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int yr, FilingStatus fs) {
                return bd("100000");
            }

            @Override
            public CombinedTaxResult computeDetailedTax(BigDecimal gross, int yr, FilingStatus fs) {
                BigDecimal total = gross.multiply(r);
                return new CombinedTaxResult(total, bd("0"), total, bd("0"), bd("0"), false);
            }
        };
    }

    // ---- factory method ----

    @Test
    void create_onlyTaxableAccounts_returnsMultiPoolWithZeroTraditionalAndRoth() {
        // Audit C11: an all-taxable account list used to dispatch to a separate, entirely untaxed
        // SinglePool (income sources never processed, SE tax never tracked, filing status hardcoded
        // SINGLE regardless of config). It now returns a real MultiPool with empty traditional/roth
        // sub-pools -- processIncomeSourcesEveryYear()/tracksSETax() flip from false to true and
        // computeEffectiveOtherIncome sums its arguments instead of discarding them: the intended
        // consequence of routing all-taxable scenarios through real taxation.
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("100000"), bd("5000"), bd("0.07"), "taxable"));
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var strategy = PoolStrategy.create(accounts, config);

        assertThat(strategy).isInstanceOf(PoolStrategy.MultiPool.class);
        assertThat(strategy.getTraditional()).isEqualByComparingTo(ZERO);
        assertThat(strategy.getTotal()).isEqualByComparingTo(bd("100000"));
        assertThat(strategy.getFilingStatus()).isEqualTo(FilingStatus.SINGLE);
        assertThat(strategy.processIncomeSourcesEveryYear()).isTrue();
        assertThat(strategy.tracksSETax()).isTrue();
        assertThat(strategy.getMagi()).isEqualByComparingTo(ZERO);
        assertThat(strategy.computeEffectiveOtherIncome(bd("1"), bd("2"))).isEqualByComparingTo(bd("3"));
        assertThat(strategy.logTag()).isEqualTo("Projection with pools");
    }

    @Test
    void create_mixedAccountTypes_returnsMultiPool() {
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("100000"), bd("0"), bd("0.07"), "taxable"),
                new HypotheticalAccountInput(bd("200000"), bd("0"), bd("0.08"), "traditional"));
        var config = new PoolStrategy.PoolConfig(FilingStatus.MARRIED_FILING_JOINTLY, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var strategy = PoolStrategy.create(accounts, config);

        assertThat(strategy).isInstanceOf(PoolStrategy.MultiPool.class);
        assertThat(strategy.getTotal()).isEqualByComparingTo(bd("300000"));
        assertThat(strategy.getFilingStatus()).isEqualTo(FilingStatus.MARRIED_FILING_JOINTLY);
        assertThat(strategy.processIncomeSourcesEveryYear()).isTrue();
        assertThat(strategy.tracksSETax()).isTrue();
        assertThat(strategy.logTag()).isEqualTo("Projection with pools");
    }

    @Test
    void create_onlyNonTaxableAccount_returnsMultiPool() {
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("50000"), bd("0"), bd("0.07"), "roth"));
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var strategy = PoolStrategy.create(accounts, config);

        assertThat(strategy).isInstanceOf(PoolStrategy.MultiPool.class);
    }

    @Test
    void create_zeroTotalBalance_weightedReturnIsZero() {
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("0"), bd("0"), bd("0.07"), "taxable"));
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var strategy = PoolStrategy.create(accounts, config);

        assertThat(strategy.getWeightedReturn()).isEqualByComparingTo(ZERO);
    }

    // ---- withdrawal ordering ----

    @Test
    void executeWithdrawals_rothFirst_drawsRothThenTaxable() {
        var p = pool("100", "200", "300", WithdrawalOrder.ROTH_FIRST);

        var r = p.executeWithdrawals(bd("350"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromRoth()).isEqualByComparingTo(bd("300"));
        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("50"));
        assertThat(r.fromTraditional()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_proRata_splitsProportionallyAcrossPools() {
        var p = pool("100", "200", "300", WithdrawalOrder.PRO_RATA);

        var r = p.executeWithdrawals(bd("60"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        // total 600, capped = 60 → each bucket gets need * balance / total
        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("10"));   // 60 * 100 / 600
        assertThat(r.fromTraditional()).isEqualByComparingTo(bd("20")); // 60 * 200 / 600
        assertThat(r.fromRoth()).isEqualByComparingTo(bd("30"));      // 60 * 300 / 600
        assertThat(r.totalWithdrawn()).isEqualByComparingTo(bd("60"));
    }

    @Test
    void executeWithdrawals_proRataWithAllPoolsEmpty_returnsZero() {
        var p = pool("0", "0", "0", WithdrawalOrder.PRO_RATA);

        var r = p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.totalWithdrawn()).isEqualByComparingTo(ZERO);
    }

    // ---- RMD forcing (T18a-1: RMD forced via a separate forceRmd() call BEFORE
    // executeWithdrawals runs -- IRS ordering, the year's RMD must be distributed before any
    // Roth conversion) ----

    @Test
    void forceRmd_capsAtTraditionalBalance_movesGrossAmountToTaxableLot() {
        var pool = poolWithRealTax("100000", "500000", "0", WithdrawalOrder.TAXABLE_FIRST);

        var forced = pool.forceRmd(bd("20000"));

        assertThat(forced).isEqualByComparingTo(bd("20000"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("480000"));
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("600000")); // unchanged: RMD stays in-portfolio
    }

    @Test
    void forceRmd_exceedsTraditionalBalance_capsAtWhateverRemains() {
        var pool = poolWithRealTax("0", "5000", "0", WithdrawalOrder.TAXABLE_FIRST);

        var forced = pool.forceRmd(bd("20000"));

        assertThat(forced).isEqualByComparingTo(bd("5000"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(ZERO);
    }

    @Test
    void forceRmd_nullOrNonPositiveAmount_isNoOp() {
        var pool = poolWithRealTax("100000", "500000", "0", WithdrawalOrder.TAXABLE_FIRST);

        assertThat(pool.forceRmd(null)).isEqualByComparingTo(ZERO);
        assertThat(pool.forceRmd(ZERO)).isEqualByComparingTo(ZERO);
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("500000"));
    }

    @Test
    void executeWithdrawals_rmdExceedsSpendDraw_forcesExtraFromTraditionalIntoTaxable() {
        var pool = poolWithRealTax("100000", "500000", "0", WithdrawalOrder.TAXABLE_FIRST);

        // T18a-1: the RMD (20000) is forced out of traditional via forceRmd() BEFORE the spend
        // draw runs; the (possibly capped) forced amount is then passed into executeWithdrawals
        // for tax attribution -- this method no longer mutates the pool for RMD purposes itself.
        var forced = pool.forceRmd(bd("20000"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("480000"));

        // Spend draw is small and taxable-first, so it comes entirely from taxable.
        var result = pool.executeWithdrawals(bd("10000"), 2025, ZERO, ZERO, forced, 75);

        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("480000")); // unchanged by the spend draw
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("20000")); // forced RMD, reported as ordinary income
        assertThat(result.taxLiability()).isGreaterThan(ZERO);
    }

    @Test
    void executeWithdrawals_spendDrawExceedsRmd_rmdForcedSeparatelyPlusAdditionalSpendDraw() {
        var pool = poolWithRealTax("100000", "500000", "0", WithdrawalOrder.TRADITIONAL_FIRST);

        // T18a-1: the RMD (20000) is forced out FIRST, unconditionally -- traditional-first spend
        // draw (60000) then pulls SEPARATELY/ADDITIONALLY from the (already RMD-reduced)
        // traditional pool, rather than partially "satisfying" the RMD the way the old post-hoc
        // force-out did. Reported traditional-sourced ordinary income is therefore the SUM
        // (80000), not just the larger of the two.
        var forced = pool.forceRmd(bd("20000"));
        var result = pool.executeWithdrawals(bd("60000"), 2025, ZERO, ZERO, forced, 75);

        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("80000"));
    }

    @Test
    void executeWithdrawals_rmdForced_totalWithdrawnIncludesRmdExcessAndReconcilesToPoolSum() {
        // T18a-5a: totalWithdrawn (the DTO's `withdrawals` aggregate) must include the forced RMD
        // excess so it reconciles with fromTaxable + fromTraditional (reported) + fromRoth -- no
        // tax calculator wired here, so there is no C2 gross-up to reopen the gap.
        var pool = pool("0", "500000", "0", WithdrawalOrder.TAXABLE_FIRST);
        var forced = pool.forceRmd(bd("20000"));

        var result = pool.executeWithdrawals(bd("5000"), YEAR, ZERO, ZERO, forced, AGE_RETIRED);

        // Spend draw (5000) comes entirely from the RMD's reinvested taxable cash; traditional's
        // OWN spend-draw pull is 0, but its reported ordinary income (20000) still reflects the
        // forced RMD.
        assertThat(result.fromTaxable()).isEqualByComparingTo(bd("5000"));
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("20000"));
        assertThat(result.fromRoth()).isEqualByComparingTo(ZERO);
        assertThat(result.totalWithdrawn()).isEqualByComparingTo(bd("25000"));
        // Sum identity: fromTaxable + fromTraditional + fromRoth == totalWithdrawn.
        assertThat(result.fromTaxable().add(result.fromTraditional()).add(result.fromRoth()))
                .isEqualByComparingTo(result.totalWithdrawn());
    }

    @Test
    void getWeightedReturn_returnsConfiguredRate() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "100", "100", "0", "0", "0"),
                bd("0.075"), new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TAXABLE_FIRST, null, null));

        assertThat(p.getWeightedReturn()).isEqualByComparingTo(bd("0.075"));
    }

    // ---- dynamic sequencing ----

    @Test
    void executeWithdrawals_dynamicSequencingEarlyAge_drawsTaxableOnly() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "500", "300", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.DYNAMIC_SEQUENCING, null, bd("0.22")));

        var r = p.executeWithdrawals(bd("150"), YEAR, ZERO, ZERO, ZERO, AGE_EARLY);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100"));
        assertThat(r.fromTraditional()).isEqualByComparingTo(ZERO);
        assertThat(r.fromRoth()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_dynamicSequencingFillsBracketFromTraditional() {
        var p = new PoolStrategy.MultiPool(
                grouped("200", "500", "100", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.DYNAMIC_SEQUENCING, flatTaxCalc("0.22"), bd("0.22")));

        // Bracket ceiling = 100000 from flatTaxCalc; all need fits, pulls from traditional up to bracket
        var r = p.executeWithdrawals(bd("150"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTraditional()).isEqualByComparingTo(bd("150"));
        assertThat(r.fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(r.fromRoth()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_dynamicSequencingNoBracketRate_fallsBackToOrderedStrategy() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "200", "300", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.DYNAMIC_SEQUENCING, null, null));

        var r = p.executeWithdrawals(bd("150"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        // Falls back to taxable-first (TAXABLE_FIRST is the default in the fallback switch)
        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100"));
        assertThat(r.fromTraditional()).isEqualByComparingTo(bd("50"));
        assertThat(r.fromRoth()).isEqualByComparingTo(ZERO);
    }

    // ---- withdrawal tax computation ----

    @Test
    void executeWithdrawals_traditionalOnlyWithTaxCalc_computesTaxAndDeductsFromPools() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "500", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null));

        // need 100 from traditional; base tax = 100 * 0.20 = 20. Taxable is $0, so that $20 itself
        // must be paid from traditional -- audit C2 gross-up, warm-started (T10 review): bill0=20;
        // first pass bill1=(100+20)*.2=24 measures chord m=(24-20)/20=0.20; closed-form jump
        // 20/(1-0.20)=25; recompute bill(125)=25 -> peek(25)==slice -> converged EXACTLY at the
        // true fixed point base*m/(1-m) (flat rate, no bracket crossing => the jump is exact).
        var r = p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTraditional()).isEqualByComparingTo(bd("125")); // 100 spend draw + 25 gross-up
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("25"));
        assertThat(r.taxSource().fromTraditional()).isEqualByComparingTo(bd("25"));
    }

    @Test
    void executeWithdrawals_withConversionAmount_computesMarginalTaxOnly() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "500", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null));

        // Conversion of 50 already taxed. Additional withdrawal 100 from traditional.
        // Base bill: detailed tax on (100+50)=30; base tax on (50)=10; marginal=30-10=20. Taxable is
        // $0, so audit C2 grosses that $20 up from traditional (the conversionAmount/baseTax netting
        // subtracts a CONSTANT, so it shifts the bill but not the chord slope): bill0=20; first pass
        // bill1=0.2*(150+20)-10=24 measures chord m=0.20; jump 20/0.80=25; bill(175)=0.2*175-10=25
        // -> converged exactly at the true fixed point 25 (flat rate, no crossing).
        var r = p.executeWithdrawals(bd("100"), YEAR, ZERO, bd("50"), ZERO, AGE_RETIRED);

        assertThat(r.taxLiability()).isEqualByComparingTo(bd("25"));
    }

    @Test
    void executeWithdrawals_noTaxCalc_zeroTaxLiability() {
        var p = pool("0", "1000", "500", WithdrawalOrder.TRADITIONAL_FIRST);

        var r = p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
        assertThat(r.taxSource().fromTaxable()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_taxDeductionCascadesAcrossPoolsWhenFirstEmpty() {
        // taxable empty → tax deduction cascades to traditional, then roth
        var p = new PoolStrategy.MultiPool(
                grouped("0", "10", "1000", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("1.0"), null));

        // Withdrawal = 10 from traditional, tax = 10 * 1.0 = 10.
        // taxable (0) < 10 → take 0. traditional (now 0 after withdrawal) < 10 → take 0. roth: -10 (unconditional subtract).
        var r = p.executeWithdrawals(bd("10"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.taxLiability()).isEqualByComparingTo(bd("10"));
        assertThat(r.taxSource().fromTaxable()).isEqualByComparingTo(ZERO);
        assertThat(r.taxSource().fromTraditional()).isEqualByComparingTo(ZERO);
        assertThat(r.taxSource().fromRoth()).isEqualByComparingTo(bd("10"));
    }

    // ---- roth conversion (fill_bracket) ----

    @Test
    void executeRothConversion_fillBracket_withinSpace_fillsUpToCeiling() {
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "500000", "0", "0", "fill_bracket", "0.24", null, tax);

        // bracketCeiling = 100000; effectiveOtherIncome = 20000; space = 80000; traditional min = 500000
        var r = p.executeRothConversion(YEAR, bd("20000"), ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("80000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("20000")); // (80000 + 20000) * 0.20
    }

    @Test
    void executeRothConversion_fillBracket_rmdReducesBracketHeadroom() {
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "500000", "0", "0", "fill_bracket", "0.24", null, tax);

        // bracketCeiling = 100000; effectiveOtherIncome = 20000; rmdAmount = 30000 already claims
        // part of the target bracket -> space = 100000 - 20000 - 30000 = 50000.
        var r = p.executeRothConversion(YEAR, bd("20000"), bd("30000"));

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("50000"));
    }

    @Test
    void executeRothConversion_fillBracket_otherIncomeAboveCeiling_returnsZero() {
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "500000", "0", "0", "fill_bracket", "0.24", null, tax);

        var r = p.executeRothConversion(YEAR, bd("150000"), ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_fixedStrategy_limitedByTraditional() {
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "30", "0", "100", "fixed", null, null, tax);

        var r = p.executeRothConversion(YEAR, ZERO, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("30")); // capped by traditional balance
    }

    @Test
    void executeRothConversion_beforeStartYear_returnsZero() {
        var p = poolWithConversion("0", "500000", "0", "5000", "fixed", null, 2040, flatTaxCalc("0.20"));

        var r = p.executeRothConversion(YEAR, ZERO, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_emptyTraditional_returnsZero() {
        var p = poolWithConversion("0", "0", "0", "5000", "fixed", null, null, flatTaxCalc("0.20"));

        var r = p.executeRothConversion(YEAR, ZERO, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_noTaxCalculator_zeroTax() {
        var p = poolWithConversion("0", "100", "0", "50", "fixed", null, null, null);

        var r = p.executeRothConversion(YEAR, ZERO, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("50"));
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    // ---- roth conversion override ----

    @Test
    void executeRothConversionOverride_positiveAmount_appliesOverride() {
        var p = poolWithConversion("0", "200", "10", "5000", "fixed", null, null, flatTaxCalc("0.10"));

        var r = p.executeRothConversionOverride(YEAR, ZERO, bd("120"), ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("120"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("12"));
    }

    @Test
    void executeRothConversionOverride_zeroAmount_returnsZero() {
        var p = poolWithConversion("0", "200", "0", "5000", "fixed", null, null, flatTaxCalc("0.20"));

        var r = p.executeRothConversionOverride(YEAR, ZERO, ZERO, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversionOverride_allTaxableAccounts_emptyTraditional_returnsZero() {
        // Audit C11: an all-taxable account list is now a MultiPool with an empty traditional
        // sub-pool, so MultiPool's own override guard (traditional <= 0) short-circuits -- there is
        // no more SinglePool default-method delegation path to exercise here.
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        var allTaxable = PoolStrategy.create(
                List.<ProjectionAccountInput>of(
                        new HypotheticalAccountInput(bd("100000"), ZERO, ZERO, "taxable")),
                config);

        var r = allTaxable.executeRothConversionOverride(YEAR, ZERO, bd("1000"), ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    // ---- growth / contribution / floor / deposit ----

    @Test
    void applyContributions_addsToEachPool() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "200", "50", "10", "20", "5"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TAXABLE_FIRST, null, null));

        var total = p.applyContributions();

        assertThat(total).isEqualByComparingTo(bd("35"));
        assertThat(p.getTotal()).isEqualByComparingTo(bd("385"));
    }

    @Test
    void applyGrowth_producesGrowthPerPool() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "200", "100", "0", "0", "0"),
                bd("0.10"), new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TAXABLE_FIRST, null, null));

        var g = p.applyGrowth(true);

        assertThat(g.taxable()).isEqualByComparingTo(bd("10"));
        assertThat(g.traditional()).isEqualByComparingTo(bd("20"));
        assertThat(g.roth()).isEqualByComparingTo(bd("10"));
        assertThat(g.total()).isEqualByComparingTo(bd("40"));
        assertThat(p.getTotal()).isEqualByComparingTo(bd("440"));
    }

    @Test
    void floorAtZero_clampsNegativePools() {
        var p = pool("100", "50", "25", WithdrawalOrder.TAXABLE_FIRST);
        p.executeWithdrawals(bd("175"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        // after withdrawal traditional is at 0, roth at 0

        p.floorAtZero();

        assertThat(p.getTotal()).isEqualByComparingTo(ZERO);
    }

    @Test
    void depositToTaxable_increasesTaxablePool() {
        var p = pool("50", "100", "100", WithdrawalOrder.TAXABLE_FIRST);

        p.depositToTaxable(bd("25"));

        assertThat(p.getTotal()).isEqualByComparingTo(bd("275"));
        // confirm the deposit targets the taxable bucket: withdraw with TAXABLE_FIRST and check
        var r = p.executeWithdrawals(bd("75"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("75"));
    }

    @Test
    void computeEffectiveOtherIncome_sumsAllComponents() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "100", "0", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, bd("10000"), ZERO, "fixed", null, null,
                        WithdrawalOrder.TAXABLE_FIRST, null, null));

        var effective = p.computeEffectiveOtherIncome(bd("5000"), bd("2000"));

        assertThat(effective).isEqualByComparingTo(bd("17000"));
    }

    @Test
    void getMagi_returnsOtherIncome() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "100", "0", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.MARRIED_FILING_JOINTLY, bd("55000"), ZERO, "fixed", null, null,
                        WithdrawalOrder.TAXABLE_FIRST, null, null));

        assertThat(p.getMagi()).isEqualByComparingTo(bd("55000"));
        assertThat(p.getFilingStatus()).isEqualTo(FilingStatus.MARRIED_FILING_JOINTLY);
    }

    @Test
    void getLastTaxBreakdown_exposesBreakdownAfterWithdrawalWithTaxCalc() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null));

        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        // Taxable is $0: audit C2 grosses the $20 base bill up to the exact fixed point $25 (same
        // warm-started recursion as executeWithdrawals_traditionalOnlyWithTaxCalc_... above), and
        // the breakdown recorded is the FINAL (converged) computeDetailedTax call: 0.2*(100+25)=25.
        assertThat(p.getLastTaxBreakdown()).isPresent();
        assertThat(p.getLastTaxBreakdown().get().totalTax()).isEqualByComparingTo(bd("25"));
    }

    // ---- buildYearDto ----

    @Test
    void buildYearDto_withTaxBreakdown_populatesFederalAndStateTaxFields() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TRADITIONAL_FIRST,
                new TaxCalculationStrategy() {
                    @Override
                    public BigDecimal computeTotalTax(BigDecimal g, int y, FilingStatus fs) {
                        return g.multiply(bd("0.20"));
                    }

                    @Override
                    public BigDecimal computeMaxIncomeForTargetRate(BigDecimal r, int y, FilingStatus fs) {
                        return bd("100000");
                    }

                    @Override
                    public CombinedTaxResult computeDetailedTax(BigDecimal g, int y, FilingStatus fs) {
                        return new CombinedTaxResult(bd("15"), bd("5"), bd("20"), bd("3"), bd("10"), true);
                    }
                }, null));

        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var dto = p.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("1100"), ZERO, ZERO,
                bd("100"), true, ZERO, bd("20"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                ZERO, bd("100"), ZERO,
                new PoolStrategy.TaxSourceResult(ZERO, bd("20"), ZERO), ZERO, ZERO, ZERO));

        assertThat(dto.federalTax()).isEqualByComparingTo(bd("15"));
        assertThat(dto.stateTax()).isEqualByComparingTo(bd("5"));
        assertThat(dto.saltDeduction()).isEqualByComparingTo(bd("3"));
        assertThat(dto.usedItemizedDeduction()).isTrue();
    }

    @Test
    void buildYearDto_noTaxBreakdown_leavesFieldsNull() {
        var p = pool("100", "0", "0", WithdrawalOrder.TAXABLE_FIRST);

        var dto = p.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("100"), ZERO, ZERO,
                bd("50"), true, ZERO, ZERO,
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                bd("50"), ZERO, ZERO,
                PoolStrategy.TaxSourceResult.ZERO, ZERO, ZERO, ZERO));

        assertThat(dto.federalTax()).isNull();
        assertThat(dto.stateTax()).isNull();
        assertThat(dto.saltDeduction()).isNull();
        assertThat(dto.usedItemizedDeduction()).isNull();
    }

    @Test
    void buildYearDto_stateTaxZero_leavesStateTaxNull() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null));

        // taxable being $0, audit C2 grosses the $100 traditional draw's own 20% tax up to the exact
        // fixed point 25 (100 need + tax = taxableIncome; tax = 0.20*(100+tax) => tax = 25) -- same
        // warm-started recursion as the sibling tests. The YearDtoContext's own taxLiability below
        // matches that same figure: T23 item 1 made MultiPoolYearDtoBuilder suppress the pool's
        // stored lastTaxBreakdown whenever it DISAGREES with the caller-supplied taxLiability (a
        // stale breakdown -- see that class's javadoc), so this test must supply the true converged
        // liability, not an arbitrary mismatched one, for federalTax to surface at all.
        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var dto = p.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("1100"), ZERO, ZERO, bd("100"), true,
                ZERO, bd("25"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                ZERO, bd("100"), ZERO, PoolStrategy.TaxSourceResult.ZERO, ZERO, ZERO, ZERO));

        assertThat(dto.federalTax()).isEqualByComparingTo(bd("25"));
        assertThat(dto.stateTax()).isNull();
        assertThat(dto.saltDeduction()).isNull();
    }

    /**
     * T23 item 1: end-to-end (pool + builder) pin of the stale-breakdown suppression, complementing
     * {@code MultiPoolYearDtoBuilderTest}'s pure-builder-level pin. Same fixture as the sibling test
     * above (converged gross-up fixed point $25), but the caller now supplies a MISMATCHED
     * {@code taxLiability} ($20, a different year's/branch's figure) -- exactly the "federal/state
     * breakdown without its taxLiability" defect the item targets. The stored breakdown must be
     * suppressed entirely, not partially surfaced.
     */
    @Test
    void buildYearDto_taxLiabilityDisagreesWithStoredBreakdown_suppressesStaleFederalTax() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                        WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null));

        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var dto = p.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("1100"), ZERO, ZERO, bd("100"), true,
                ZERO, bd("20"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                ZERO, bd("100"), ZERO, PoolStrategy.TaxSourceResult.ZERO, ZERO, ZERO, ZERO));

        assertThat(dto.taxLiability()).isEqualByComparingTo(bd("20"));
        assertThat(dto.federalTax()).isNull();
        assertThat(dto.stateTax()).isNull();
        assertThat(dto.saltDeduction()).isNull();
        assertThat(dto.usedItemizedDeduction()).isNull();
    }

    // ---- all-taxable MultiPool spot-checks (audit C11: formerly SinglePool) ----

    @Test
    void allTaxableAccounts_applyContributionsGrowthWithdrawFloorDeposit() {
        // Bit-identical arithmetic to the pre-C11 SinglePool spot-check: no tax calculator is wired,
        // so this pins balance/growth/withdrawal math only, not the taxation consequence of C11.
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        var pool = PoolStrategy.create(
                List.<ProjectionAccountInput>of(
                        new HypotheticalAccountInput(bd("1000"), bd("100"), bd("0.10"), "taxable")),
                config);
        assertThat(pool).isInstanceOf(PoolStrategy.MultiPool.class);

        pool.applyContributions();
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("1100"));

        var g = pool.applyGrowth(true);
        assertThat(g.total()).isEqualByComparingTo(bd("110"));

        var w = pool.executeWithdrawals(bd("50"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        assertThat(w.totalWithdrawn()).isEqualByComparingTo(bd("50"));

        pool.depositToTaxable(bd("25"));
        pool.floorAtZero(); // positive traditional/roth (both zero) → no-op
        assertThat(pool.getTotal()).isEqualByComparingTo(bd("1185"));
    }

    // Note: the pre-C11 SinglePool "floorAtZero clamps a negative balance driven by
    // depositToTaxable(negative)" spot-check has no MultiPool equivalent -- MultiPool's
    // floorAtZero() only clamps the scalar traditional/roth pools (see its javadoc: taxable lots
    // can never go negative via the real sellFifo path), so a synthetic negative deposit into the
    // taxable lots is not floored. depositToTaxable is only ever called in production with a
    // positive surplus (RetirementWithdrawalProcessor), so this is not a reachable regression;
    // floorAtZero_clampsNegativePools above already covers the real (traditional/roth) invariant.

    // Note: the pre-C11 SinglePool "executeRothConversion always returns zero" spot-check is
    // superseded by executeRothConversion_emptyTraditional_returnsZero above, which already covers
    // MultiPool's real reason for returning zero when there is no traditional balance to convert.

    // === T18a-4: 10% IRC 72(t) early-withdrawal penalty on pre-59½ traditional distributions ===

    @Test
    void executeWithdrawals_traditionalDrawBeforeAge60_appliesTenPercentPenalty() {
        // Taxable ($50,000) comfortably funds the ordinary tax AND the penalty, so gross-up never
        // triggers -- isolates the penalty math from audit C2's fixed point.
        var pool = poolWithOrderAndTax("50000", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST,
                flatTaxCalc("0.10"));

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO, AGE_EARLY,
                ZERO, ZERO, ZERO);

        // Ordinary tax: 10000 * 10% = 1000. Penalty: 10000 (the full traditional distribution,
        // matching traditionalOrdinaryIncome) * 10% = 1000. taxLiability = both, additive.
        assertThat(result.taxLiability()).isEqualByComparingTo(bd("2000"));
        assertThat(result.earlyWithdrawalPenalty()).isEqualByComparingTo(bd("1000"));
        // The penalty is funded from taxable (which has plenty), NOT re-added to traditional's
        // reported ordinary income -- fromTraditional stays exactly the distribution amount.
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("10000"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("90000")); // untouched by tax/penalty funding
        // Taxable funds both the $1,000 ordinary tax and the $1,000 penalty: 50000 - 2000 = 48000.
    }

    @Test
    void executeWithdrawals_traditionalDrawAtOrAfterAge60_noPenalty() {
        var pool = poolWithOrderAndTax("50000", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST,
                flatTaxCalc("0.10"));

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO,
                RetirementAges.EARLY_WITHDRAWAL_AGE, ZERO, ZERO, ZERO);

        assertThat(result.earlyWithdrawalPenalty()).isEqualByComparingTo(ZERO);
        assertThat(result.taxLiability()).isEqualByComparingTo(bd("1000")); // ordinary tax only
    }

    @Test
    void executeWithdrawals_noTraditionalDrawBeforeAge60_noPenaltyOnTaxableOnlySpend() {
        // Taxable-first order with a small draw fully covered by taxable -- zero traditional
        // distribution, so no penalty applies even though age is under 60.
        var pool = poolWithOrderAndTax("50000", "100000", "0", WithdrawalOrder.TAXABLE_FIRST,
                flatTaxCalc("0.10"));

        var result = pool.executeWithdrawals(bd("10000"), YEAR, ZERO, ZERO, ZERO, AGE_EARLY,
                ZERO, ZERO, ZERO);

        assertThat(result.fromTraditional()).isEqualByComparingTo(ZERO);
        assertThat(result.earlyWithdrawalPenalty()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_beforeAge60_notPenalized() {
        // T18a-4 explicitly scopes OUT Roth conversions -- the converted dollars move internally
        // to Roth, they are not withdrawn to the household, so the 10% penalty never applies to a
        // conversion regardless of age. (executeRothConversion has no age parameter at all --
        // this test documents that the penalty logic lives entirely in executeWithdrawals.)
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "500000", "0", "50000", "fixed", null, null, tax);

        var r = p.executeRothConversion(YEAR, ZERO, ZERO);

        // Ordinary conversion tax only (50000 * 20% = 10000) -- no penalty component exists on
        // ConversionResult at all.
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("10000"));
    }

    // === Audit C2: tax paid FROM the traditional pool must gross up (the draw is itself taxable) ===

    @Test
    void executeWithdrawals_taxFullyCoveredByTaxable_noGrossUp() {
        // C2 control case: when the taxable pool alone can fund the ENTIRE tax bill, there is no
        // traditional slice to gross up -- byte-identical to pre-C2 behavior. Traditional-first
        // order keeps the $1,000 spend draw off the taxable pool entirely, isolating "does the TAX
        // payment itself touch traditional" as the only variable.
        var pool = poolWithOrderAndTax("5000", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST,
                flatTaxCalc("0.10"));

        var result = pool.executeWithdrawals(bd("1000"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED,
                ZERO, ZERO, ZERO);

        // Spend draw (1000) entirely from traditional (traditional-first order); the resulting $100
        // tax bill is then fully funded by the untouched $5,000 taxable pool.
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("1000"));
        assertThat(result.taxLiability()).isEqualByComparingTo(bd("100")); // 10% flat on the $1,000 draw
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("99000")); // untouched by the $100 tax
    }

    @Test
    void executeWithdrawals_rmdForcedFirst_rmdCashFundsTaxWithNoGrossUp() {
        // T18a-1: because the RMD (8,100) is forced out of traditional BEFORE this call runs
        // (its after-tax-free gross proceeds already sit in taxable as $8,100 cash), the
        // withdrawal tax bill on this year's traditional-sourced income (spend draw 8,000 + RMD
        // 8,100 = 16,100 @ 10% = 1,610) is comfortably absorbed by that taxable cash alone -- NO
        // audit-C2 gross-up is needed. Pre-T18a-1, only a $100 RMD "excess" reached taxable
        // BEFORE the tax cascade ran (see the fixed-point test below), forcing a small gross-up;
        // RMD-first ordering makes the FULL RMD's cash available up front, eliminating it here.
        var pool = poolWithOrderAndTax("0", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.10"));
        var forced = pool.forceRmd(bd("8100"));

        var result = pool.executeWithdrawals(bd("8000"), YEAR, ZERO, ZERO, forced, AGE_RETIRED,
                ZERO, ZERO, ZERO);

        assertThat(result.taxLiability()).isEqualByComparingTo(bd("1610")); // 10% of (8,000 spend + 8,100 rmd)
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("16100")); // no gross-up added
        // Traditional debited by forceRmd (8,100) + the spend draw (8,000) only -- the tax is paid
        // entirely from the RMD's own taxable cash, so traditional isn't touched again.
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("83900"));
    }

    @Test
    void executeWithdrawals_rmdForcedFirstThenGrossUp_convergesToSameFixedPointAsPreT18a1() {
        // T18a-1 + C2 interaction: even with the RMD forced out FIRST (via a separate forceRmd()
        // call, not this method), the audit-C2 gross-up machinery inside executeWithdrawals is
        // unchanged -- it still converges to the TRUE fixed point once the post-RMD-force,
        // post-spend-draw taxable cash can't cover the whole bill. A small RMD (100, forced
        // first) plus an $8,000 traditional spend draw (pulled from the ALREADY-reduced
        // traditional pool) reproduces the EXACT SAME numbers as the pre-T18a-1 fixed-point pin,
        // because taxable ends up holding the same $100 either way: an "independent closed-form
        // oracle" derives the TRUE fixed point directly against the flat 10% strategy: slice
        // solves s = 0.10*(8,100+s) - 100 => 0.9s = 710 => s* = 710/0.9 = 788.88888889
        // (scale-8 HALF_UP, matching the warm-start jump's division), bill* =
        // 0.10*(8,100+788.88888889) = 888.888888889.
        BigDecimal slice = bd("710").divide(bd("0.9"), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal bill = bd("8100").add(slice).multiply(bd("0.10"));
        assertThat(slice).isEqualByComparingTo(bd("788.88888889"));
        assertThat(bill).isEqualByComparingTo(bd("888.888888889"));

        var pool = poolWithOrderAndTax("0", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.10"));
        var forced = pool.forceRmd(bd("100"));
        assertThat(forced).isEqualByComparingTo(bd("100"));

        var result = pool.executeWithdrawals(bd("8000"), YEAR, ZERO, ZERO, forced, AGE_RETIRED,
                ZERO, ZERO, ZERO);

        // taxLiability matches the oracle's true fixed point exactly (flat rate => warm jump exact).
        assertThat(result.taxLiability()).isEqualByComparingTo(bill);
        // Reported ordinary income (feeds the audit-B2 SS convergence loop and RetirementTaxAnnotator)
        // = (forced RMD + spend draw) + the converged gross-up slice.
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("8100").add(slice));
        // Traditional is debited by forceRmd (100) + the spend draw (8,000) + the ACTUAL cascade
        // drain funding the converged bill (taxable's $100 reinvested-RMD lot covers the first
        // $100, the rest -- 888.8889-100=788.8889 -- comes from traditional).
        BigDecimal actualTraditionalTaxDrain = bill.subtract(bd("100"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(
                bd("100000").subtract(bd("100")).subtract(bd("8000")).subtract(actualTraditionalTaxDrain));
    }

    // === T10 review: gross-up residual vs the TRUE fixed point must be < $1 ===

    @Test
    void executeWithdrawals_grossUp22PercentBracket_convergesWithinOneDollarOfClosedForm() {
        // Residual assertion (T10 review): real single-2025 brackets, $80,000 traditional draw
        // (taxable income 65,000, mid-22% bracket), taxable pool $0. The whole gross-up range stays
        // inside the 22% bracket (fixed-point income ~91,813 -> taxable ~76,813 < 103,350), so the
        // TRUE fixed point has the exact closed form B0/(1-0.22): B0 = computeTax(80,000) = 9,214.00
        // -> bill* = 9,214/0.78 = 11,812.820513. Pre-fix (cold start, 5 passes) the engine landed
        // ~$2.9 short of this; the warm-started fixed point must land within $1.
        var pool = poolWithRealTax("0", "1000000", "0", WithdrawalOrder.TRADITIONAL_FIRST);
        BigDecimal closedForm = referenceSingle2025Tax(bd("80000"))
                .divide(bd("0.78"), 8, java.math.RoundingMode.HALF_UP);
        assertThat(referenceSingle2025Tax(bd("80000"))).isEqualByComparingTo(bd("9214.00"));

        var result = pool.executeWithdrawals(bd("80000"), 2025, ZERO, ZERO, ZERO, AGE_RETIRED,
                ZERO, ZERO, ZERO);

        assertThat(result.taxLiability().subtract(closedForm).abs()).isLessThan(bd("1"));
    }

    @Test
    void executeWithdrawals_grossUp32PercentCrossingInto35_convergesWithinOneDollarOfTrueFixedPoint() {
        // Residual assertion (T10 review), bracket-crossing stress: $250,000 draw (taxable 235,000,
        // 32% bracket) -- the ~$79.7k gross-up pushes fixed-point taxable income past 250,525 into
        // the 35% bracket, so no single-bracket closed form exists. Independent high-precision
        // oracle: 100 plain fixed-point iterations of bill = computeTax(250,000 + bill) against the
        // same real FederalTaxCalculator (contraction at 0.35 per pass => converged far below $0.01
        // by iteration 100). Pre-fix the engine landed >$100 short here; the warm start + polish
        // must land within $1 of the true fixed point.
        var pool = poolWithRealTax("0", "1000000", "0", WithdrawalOrder.TRADITIONAL_FIRST);
        BigDecimal trueBill = BigDecimal.ZERO;
        for (int i = 0; i < 100; i++) {
            trueBill = referenceSingle2025Tax(bd("250000").add(trueBill));
        }

        var result = pool.executeWithdrawals(bd("250000"), 2025, ZERO, ZERO, ZERO, AGE_RETIRED,
                ZERO, ZERO, ZERO);

        assertThat(result.taxLiability().subtract(trueBill).abs()).isLessThan(bd("1"));
    }

    /** Independent single-2025 federal tax oracle for the residual tests: a FRESH FederalTaxCalculator
     * over the same bracket fixtures, called directly (not through MultiPool's machinery). */
    private BigDecimal referenceSingle2025Tax(BigDecimal grossIncome) {
        var taxBracketRepository = mock(TaxBracketRepository.class);
        var standardDeductionRepository = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        return new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository)
                .computeTax(grossIncome, 2025, FilingStatus.SINGLE);
    }
}
