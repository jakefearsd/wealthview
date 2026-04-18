package com.wealthview.projection;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static org.assertj.core.api.Assertions.assertThat;

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
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth, "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                order, null, null);
    }

    private PoolStrategy.MultiPool poolWithConversion(String taxable, String traditional, String roth,
                                                        String annualConv, String rothStrategy,
                                                        String targetRate, Integer startYear,
                                                        TaxCalculationStrategy taxCalc) {
        return new PoolStrategy.MultiPool(
                grouped(taxable, traditional, roth, "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO,
                bd(annualConv), rothStrategy,
                targetRate != null ? bd(targetRate) : null,
                startYear, WithdrawalOrder.TAXABLE_FIRST, taxCalc, null);
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
        assertThat(strategy.getFilingStatusString()).isEqualTo("single");
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
        assertThat(strategy.getFilingStatusString()).isEqualTo("married_filing_jointly");
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

    @Test
    void getWeightedReturn_returnsConfiguredRate() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "100", "100", "0", "0", "0"),
                bd("0.075"), FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        assertThat(p.getWeightedReturn()).isEqualByComparingTo(bd("0.075"));
    }

    // ---- dynamic sequencing ----

    @Test
    void executeWithdrawals_dynamicSequencingEarlyAge_drawsTaxableOnly() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "500", "300", "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.DYNAMIC_SEQUENCING, null, bd("0.22"));

        var r = p.executeWithdrawals(bd("150"), YEAR, ZERO, ZERO, ZERO, AGE_EARLY);

        assertThat(r.fromTaxable()).isEqualByComparingTo(bd("100"));
        assertThat(r.fromTraditional()).isEqualByComparingTo(ZERO);
        assertThat(r.fromRoth()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeWithdrawals_dynamicSequencingFillsBracketFromTraditional() {
        var p = new PoolStrategy.MultiPool(
                grouped("200", "500", "100", "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.DYNAMIC_SEQUENCING, flatTaxCalc("0.22"), bd("0.22"));

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
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.DYNAMIC_SEQUENCING, null, null);

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
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null);

        // need 100 from traditional; tax = 100 * 0.20 = 20, deducted from pools (taxable=0 → traditional)
        var r = p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(r.fromTraditional()).isEqualByComparingTo(bd("100"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("20"));
        assertThat(r.taxSource().fromTraditional()).isEqualByComparingTo(bd("20"));
    }

    @Test
    void executeWithdrawals_withConversionAmount_computesMarginalTaxOnly() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "500", "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null);

        // Conversion of 50 already taxed. Additional withdrawal 100 from traditional.
        // detailed tax on (100+50) = 30; base tax on (50) = 10; marginal = 30 - 10 = 20
        var r = p.executeWithdrawals(bd("100"), YEAR, ZERO, bd("50"), ZERO, AGE_RETIRED);

        assertThat(r.taxLiability()).isEqualByComparingTo(bd("20"));
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
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("1.0"), null);

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
        var r = p.executeRothConversion(YEAR, bd("20000"));

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("80000"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("20000")); // (80000 + 20000) * 0.20
    }

    @Test
    void executeRothConversion_fillBracket_otherIncomeAboveCeiling_returnsZero() {
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "500000", "0", "0", "fill_bracket", "0.24", null, tax);

        var r = p.executeRothConversion(YEAR, bd("150000"));

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_fixedStrategy_limitedByTraditional() {
        var tax = flatTaxCalc("0.20");
        var p = poolWithConversion("0", "30", "0", "100", "fixed", null, null, tax);

        var r = p.executeRothConversion(YEAR, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("30")); // capped by traditional balance
    }

    @Test
    void executeRothConversion_beforeStartYear_returnsZero() {
        var p = poolWithConversion("0", "500000", "0", "5000", "fixed", null, 2040, flatTaxCalc("0.20"));

        var r = p.executeRothConversion(YEAR, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_emptyTraditional_returnsZero() {
        var p = poolWithConversion("0", "0", "0", "5000", "fixed", null, null, flatTaxCalc("0.20"));

        var r = p.executeRothConversion(YEAR, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    @Test
    void executeRothConversion_noTaxCalculator_zeroTax() {
        var p = poolWithConversion("0", "100", "0", "50", "fixed", null, null, null);

        var r = p.executeRothConversion(YEAR, ZERO);

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("50"));
        assertThat(r.taxLiability()).isEqualByComparingTo(ZERO);
    }

    // ---- roth conversion override ----

    @Test
    void executeRothConversionOverride_positiveAmount_appliesOverride() {
        var p = poolWithConversion("0", "200", "10", "5000", "fixed", null, null, flatTaxCalc("0.10"));

        var r = p.executeRothConversionOverride(YEAR, ZERO, bd("120"));

        assertThat(r.amountConverted()).isEqualByComparingTo(bd("120"));
        assertThat(r.taxLiability()).isEqualByComparingTo(bd("12"));
    }

    @Test
    void executeRothConversionOverride_zeroAmount_returnsZero() {
        var p = poolWithConversion("0", "200", "0", "5000", "fixed", null, null, flatTaxCalc("0.20"));

        var r = p.executeRothConversionOverride(YEAR, ZERO, ZERO);

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

        var r = single.executeRothConversionOverride(YEAR, ZERO, bd("1000"));

        // SinglePool.executeRothConversion returns ZERO → default delegates to same
        assertThat(r.amountConverted()).isEqualByComparingTo(ZERO);
    }

    // ---- growth / contribution / floor / deposit ----

    @Test
    void applyContributions_addsToEachPool() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "200", "50", "10", "20", "5"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var total = p.applyContributions();

        assertThat(total).isEqualByComparingTo(bd("35"));
        assertThat(p.getTotal()).isEqualByComparingTo(bd("385"));
    }

    @Test
    void applyGrowth_producesGrowthPerPool() {
        var p = new PoolStrategy.MultiPool(
                grouped("100", "200", "100", "0", "0", "0"),
                bd("0.10"), FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

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
                ZERO, FilingStatus.SINGLE, bd("10000"), ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        var effective = p.computeEffectiveOtherIncome(bd("5000"), bd("2000"));

        assertThat(effective).isEqualByComparingTo(bd("17000"));
    }

    @Test
    void getMagi_returnsOtherIncome() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "100", "0", "0", "0", "0"),
                ZERO, FilingStatus.MARRIED_FILING_JOINTLY, bd("55000"), ZERO, "fixed", null, null,
                WithdrawalOrder.TAXABLE_FIRST, null, null);

        assertThat(p.getMagi()).isEqualByComparingTo(bd("55000"));
        assertThat(p.getFilingStatusString()).isEqualTo("married_filing_jointly");
    }

    @Test
    void getLastTaxBreakdown_exposesBreakdownAfterWithdrawalWithTaxCalc() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null);

        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);

        assertThat(p.getLastTaxBreakdown()).isNotNull();
        assertThat(p.getLastTaxBreakdown().totalTax()).isEqualByComparingTo(bd("20"));
    }

    // ---- buildYearDto ----

    @Test
    void buildYearDto_withTaxBreakdown_populatesFederalAndStateTaxFields() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
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
                }, null);

        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var dto = p.buildYearDto(YEAR, AGE_RETIRED, bd("1100"), ZERO, ZERO,
                bd("100"), true, ZERO, bd("20"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                ZERO, bd("100"), ZERO,
                new PoolStrategy.TaxSourceResult(ZERO, bd("20"), ZERO));

        assertThat(dto.federalTax()).isEqualByComparingTo(bd("15"));
        assertThat(dto.stateTax()).isEqualByComparingTo(bd("5"));
        assertThat(dto.saltDeduction()).isEqualByComparingTo(bd("3"));
        assertThat(dto.usedItemizedDeduction()).isTrue();
    }

    @Test
    void buildYearDto_noTaxBreakdown_leavesFieldsNull() {
        var p = pool("100", "0", "0", WithdrawalOrder.TAXABLE_FIRST);

        var dto = p.buildYearDto(YEAR, AGE_RETIRED, bd("100"), ZERO, ZERO,
                bd("50"), true, ZERO, ZERO,
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                bd("50"), ZERO, ZERO,
                PoolStrategy.TaxSourceResult.ZERO);

        assertThat(dto.federalTax()).isNull();
        assertThat(dto.stateTax()).isNull();
        assertThat(dto.saltDeduction()).isNull();
        assertThat(dto.usedItemizedDeduction()).isNull();
    }

    @Test
    void buildYearDto_stateTaxZero_leavesStateTaxNull() {
        var p = new PoolStrategy.MultiPool(
                grouped("0", "1000", "100", "0", "0", "0"),
                ZERO, FilingStatus.SINGLE, ZERO, ZERO, "fixed", null, null,
                WithdrawalOrder.TRADITIONAL_FIRST, flatTaxCalc("0.20"), null);

        p.executeWithdrawals(bd("100"), YEAR, ZERO, ZERO, ZERO, AGE_RETIRED);
        var dto = p.buildYearDto(YEAR, AGE_RETIRED, bd("1100"), ZERO, ZERO, bd("100"), true,
                ZERO, bd("20"),
                new PoolStrategy.GrowthResult(ZERO, ZERO, ZERO, ZERO),
                ZERO, bd("100"), ZERO, PoolStrategy.TaxSourceResult.ZERO);

        assertThat(dto.federalTax()).isEqualByComparingTo(bd("20"));
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

        assertThat(sp.executeRothConversion(YEAR, ZERO).amountConverted()).isEqualByComparingTo(ZERO);
    }
}
