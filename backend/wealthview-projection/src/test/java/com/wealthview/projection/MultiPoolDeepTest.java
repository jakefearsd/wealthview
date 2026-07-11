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
    void create_onlyTaxableAccounts_returnsSinglePool() {
        var accounts = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("100000"), bd("5000"), bd("0.07"), "taxable"));
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var strategy = PoolStrategy.create(accounts, config);

        assertThat(strategy).isInstanceOf(PoolStrategy.SinglePool.class);
        assertThat(strategy.getTotal()).isEqualByComparingTo(bd("100000"));
        assertThat(strategy.getFilingStatus()).isEqualTo(FilingStatus.SINGLE);
        assertThat(strategy.processIncomeSourcesEveryYear()).isFalse();
        assertThat(strategy.tracksSETax()).isFalse();
        assertThat(strategy.getMagi()).isEqualByComparingTo(ZERO);
        assertThat(strategy.computeEffectiveOtherIncome(bd("1"), bd("2"))).isEqualByComparingTo(ZERO);
        assertThat(strategy.logTag()).isEqualTo("Projection");
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

    // ---- RMD forcing ----

    @Test
    void executeWithdrawals_rmdExceedsSpendDraw_forcesExtraFromTraditionalIntoTaxable() {
        var pool = poolWithRealTax("100000", "500000", "0", WithdrawalOrder.TAXABLE_FIRST);

        // Spend draw is small and taxable-first, so it comes entirely from taxable → fromTraditional ~= 0.
        // The RMD (20000) must still be forced out of traditional and reinvested (gross) to taxable.
        var result = pool.executeWithdrawals(bd("10000"), 2025, ZERO, ZERO, bd("20000"), 75);

        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("480000"));
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("20000"));
        assertThat(result.taxLiability()).isGreaterThan(ZERO);
    }

    @Test
    void executeWithdrawals_spendDrawExceedsRmd_noForcedExtra() {
        var pool = poolWithRealTax("100000", "500000", "0", WithdrawalOrder.TRADITIONAL_FIRST);

        // Traditional-first spend draw (60000) already exceeds the RMD (20000) → no extra forced.
        var result = pool.executeWithdrawals(bd("60000"), 2025, ZERO, ZERO, bd("20000"), 75);

        assertThat(result.fromTraditional()).isGreaterThanOrEqualTo(bd("20000"));
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
    void executeRothConversionOverride_onSinglePool_delegatesToDefault() {
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        var single = PoolStrategy.create(
                List.<ProjectionAccountInput>of(
                        new HypotheticalAccountInput(bd("100000"), ZERO, ZERO, "taxable")),
                config);

        var r = single.executeRothConversionOverride(YEAR, ZERO, bd("1000"), ZERO);

        // SinglePool.executeRothConversion returns ZERO → default delegates to same
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

        var g = p.applyGrowth();

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
                new PoolStrategy.TaxSourceResult(ZERO, bd("20"), ZERO), ZERO, ZERO));

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
                PoolStrategy.TaxSourceResult.ZERO, ZERO, ZERO));

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

        // The YearDtoContext's own taxLiability (20 below) is a caller-supplied display figure and
        // deliberately NOT re-derived here; federalTax instead comes from the pool's stored
        // lastTaxBreakdown (set by executeWithdrawals above), which -- taxable being $0 -- audit C2
        // grosses up to the exact fixed point 25 (same warm-started recursion as the sibling tests).
        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var dto = p.buildYearDto(new PoolStrategy.YearDtoContext(YEAR, AGE_RETIRED, bd("1100"), ZERO, ZERO, bd("100"), true,
                ZERO, bd("20"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                ZERO, bd("100"), ZERO, PoolStrategy.TaxSourceResult.ZERO, ZERO, ZERO));

        assertThat(dto.federalTax()).isEqualByComparingTo(bd("25"));
        assertThat(dto.stateTax()).isNull();
        assertThat(dto.saltDeduction()).isNull();
    }

    // ---- SinglePool spot-checks ----

    @Test
    void singlePool_applyContributionsGrowthWithdrawFloorDeposit() {
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        var sp = PoolStrategy.create(
                List.<ProjectionAccountInput>of(
                        new HypotheticalAccountInput(bd("1000"), bd("100"), bd("0.10"), "taxable")),
                config);

        sp.applyContributions();
        assertThat(sp.getTotal()).isEqualByComparingTo(bd("1100"));

        var g = sp.applyGrowth();
        assertThat(g.total()).isEqualByComparingTo(bd("110"));

        var w = sp.executeWithdrawals(bd("50"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        assertThat(w.totalWithdrawn()).isEqualByComparingTo(bd("50"));

        sp.depositToTaxable(bd("25"));
        sp.floorAtZero(); // positive balance → no-op
        assertThat(sp.getTotal()).isEqualByComparingTo(bd("1185"));
    }

    @Test
    void singlePool_floorAtZero_clampsNegativeBalance() {
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        var sp = PoolStrategy.create(
                List.<ProjectionAccountInput>of(
                        new HypotheticalAccountInput(bd("100"), ZERO, ZERO, "taxable")),
                config);

        sp.depositToTaxable(bd("-200"));
        sp.floorAtZero();

        assertThat(sp.getTotal()).isEqualByComparingTo(ZERO);
    }

    @Test
    void singlePool_executeRothConversion_alwaysReturnsZero() {
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);
        var sp = PoolStrategy.create(
                List.<ProjectionAccountInput>of(
                        new HypotheticalAccountInput(bd("100"), ZERO, ZERO, "taxable")),
                config);

        assertThat(sp.executeRothConversion(YEAR, ZERO, ZERO).amountConverted()).isEqualByComparingTo(ZERO);
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
    void executeWithdrawals_taxableDepletedTaxDrainsTraditional_grossesUpToFixedPoint() {
        // C2: taxable starts at $0, but the RMD force-out below reinvests its $100 excess to taxable
        // BEFORE the tax cascade runs (a real, pre-existing mechanic -- see PoolStrategy's rmdExtra
        // handling), so taxableAvail at gross-up time is $100, not $0. Above that, every extra dollar
        // of tax bill must be paid from traditional -- and that draw is itself ordinary income, which
        // raises the bill again, which raises the draw again. An "independent closed-form oracle"
        // derives the TRUE fixed point directly against the flat 10% strategy (not MultiPool's
        // private machinery): slice solves s = 0.10*(8,100+s) - 100 => 0.9s = 710 =>
        // s* = 710/0.9 = 788.88888889 (scale-8 HALF_UP, matching the warm-start jump's division),
        // bill* = 0.10*(8,100+788.88888889) = 888.888888889. The T10-review warm start lands on this
        // exactly: bill0=810, implied1=710, chord m=(881-810)/710=0.10, jump=710/0.90 -- a flat rate
        // has no bracket crossing, so the jump IS the fixed point.
        BigDecimal slice = bd("710").divide(bd("0.9"), 8, java.math.RoundingMode.HALF_UP);
        BigDecimal bill = bd("8100").add(slice).multiply(bd("0.10"));
        assertThat(slice).isEqualByComparingTo(bd("788.88888889"));
        assertThat(bill).isEqualByComparingTo(bd("888.888888889"));

        var pool = poolWithOrderAndTax("0", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.10"));

        var result = pool.executeWithdrawals(bd("8000"), YEAR, ZERO, ZERO, bd("8100"), AGE_RETIRED,
                ZERO, ZERO, ZERO);

        // taxLiability matches the oracle's true fixed point exactly (flat rate => warm jump exact).
        assertThat(result.taxLiability()).isEqualByComparingTo(bill);
        // Reported ordinary income (feeds the audit-B2 SS convergence loop and RetirementTaxAnnotator)
        // = base + the converged gross-up slice.
        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("8100").add(slice));
        // Traditional is debited by the spend draw (8,000) + forced RMD excess (100) + the ACTUAL
        // cascade drain funding the converged bill (taxable's $100 reinvested-RMD lot covers the
        // first $100, the rest -- 888.8889-100=788.8889 -- comes from traditional).
        BigDecimal actualTraditionalTaxDrain = bill.subtract(bd("100"));
        assertThat(pool.getTraditional()).isEqualByComparingTo(
                bd("100000").subtract(bd("8000")).subtract(bd("100")).subtract(actualTraditionalTaxDrain));
    }

    @Test
    void executeWithdrawals_rmdYearGrossUpDraw_countsTowardRmdSatisfactionWithoutDoubleForceOut() {
        // C2 + RMD interaction: the RMD force-out (rmdExtra) is computed ONCE, before any gross-up,
        // from the spend draw alone -- it must never be recomputed once the gross-up draw is folded
        // into ordinary income (that would force MORE than the legally-required RMD out a second
        // time). This scenario forces a small RMD excess (100) on top of an $8,000 traditional spend
        // draw with taxable at $0 -- same numbers as the fixed-point test above, so the reported
        // ordinary income (8,888.8889) comfortably exceeds the RMD (8,100) by exactly the converged
        // gross-up (788.8889 = 710/0.9, the true fixed point), never by more -- proof no second
        // force-out occurred.
        var pool = poolWithOrderAndTax("0", "100000", "0", WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.10"));

        var result = pool.executeWithdrawals(bd("8000"), YEAR, ZERO, ZERO, bd("8100"), AGE_RETIRED,
                ZERO, ZERO, ZERO);

        assertThat(result.fromTraditional()).isEqualByComparingTo(bd("8888.88888889"));
        // RMD (8,100) satisfied: reported traditional ordinary income exceeds it...
        assertThat(result.fromTraditional()).isGreaterThan(bd("8100"));
        // ...by precisely the converged gross-up, not some larger, double-forced amount.
        assertThat(result.fromTraditional().subtract(bd("8100"))).isEqualByComparingTo(bd("788.88888889"));
        // Total traditional debit = 8,000 (spend) + 100 (RMD excess) + 888.888888889 (actual tax
        // cascade: $100 from the reinvested-RMD taxable lot, the rest traditional) -- matches
        // getTraditional(), confirming no extra/unexplained draw snuck in via a second force-out.
        assertThat(pool.getTraditional()).isEqualByComparingTo(bd("91111.111111111"));
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
