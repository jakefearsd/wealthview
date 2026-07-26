package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins {@link RetirementTaxAnnotator}'s federal/state tax-breakdown assembly, in particular the
 * LTCG-into-federalTax fold: LTCG is a federal tax, so it must be added to the recomputed
 * ordinary-tax breakdown's federal component so {@code federalTax + stateTax == taxLiability}.
 */
class RetirementTaxAnnotatorTest {

    private static final int YEAR = 2040;
    private static final int AGE = 65;

    private final RetirementTaxAnnotator annotator = new RetirementTaxAnnotator();

    private ProjectionYearDto baseYearDto() {
        return ProjectionYearDto.simple(YEAR, AGE, BigDecimal.valueOf(1_000_000), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.valueOf(50_000), BigDecimal.valueOf(950_000), true);
    }

    /**
     * PoolStrategy is sealed with {@code MultiPool} as its sole implementation (audit C11 retired
     * the SinglePool branch) and cannot be mocked; an empty-accounts MultiPool configured with
     * FilingStatus.SINGLE is all {@code annotate()} needs from the pool in these tests.
     */
    private static PoolStrategy filingStatusOnlyPool() {
        var config = PoolFixtures.singleFilerConfig(WithdrawalOrder.TAXABLE_FIRST);
        return new PoolStrategy.MultiPool(Map.of(), BigDecimal.ZERO, config);
    }

    @Test
    void annotate_retiredYearWithLtcgTax_foldsLtcgIntoFederalTaxComponent() {
        // Ordinary tax on $0 taxable income (no traditional withdrawal/conversion/other income
        // this year) is zero, but the year still owes $5,308.2164 of LTCG tax on a taxable-pool
        // sale -- the bug this fold fixes: federalTax used to stay 0 while taxLiability did not.
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(CombinedTaxResult.ZERO);
        PoolStrategy pool = filingStatusOnlyPool();

        var ltcgTax = new BigDecimal("5308.2164");
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, ltcgTax, pool, taxStrategy, ltcgTax,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ltcgTax);
        assertThat(result.stateTax()).isNull();
    }

    @Test
    void annotate_retiredYearWithOrdinaryAndLtcgTax_federalTaxIsSumOfBoth() {
        // Ordinary federal/state tax on the withdrawal + conversion + other income, PLUS a
        // separately-realized LTCG tax -- both are federal, so both must land in federalTax.
        var ordinaryFederal = new BigDecimal("8000.0000");
        var ordinaryState = new BigDecimal("1200.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, ordinaryState,
                ordinaryFederal.add(ordinaryState), BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var ltcgTax = new BigDecimal("1964.6018");
        var totalTaxLiability = ordinaryFederal.add(ordinaryState).add(ltcgTax);
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.valueOf(40_000), BigDecimal.ZERO, BigDecimal.ZERO,
                totalTaxLiability, pool, taxStrategy, ltcgTax, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal.add(ltcgTax));
        assertThat(result.stateTax()).isEqualByComparingTo(ordinaryState);
        // The whole point of the fold: federalTax + stateTax now reconciles with the year's total
        // taxLiability (the value the engine passed in), instead of silently dropping the LTCG slice.
        assertThat(result.federalTax().add(result.stateTax())).isEqualByComparingTo(totalTaxLiability);
    }

    /**
     * T23 item 3: {@code annotate} previously gated its whole fold on {@code retired}, so a
     * pre-retirement year with real tax activity (e.g. an accumulation-year Roth conversion with
     * {@code rothConversionStartYear} set) showed a positive {@code taxLiability} with NO federal/
     * state breakdown at all -- a display gap, since the pool-level funding for that tax is already
     * correct regardless of retirement status (T18a-2). The fold must apply to any year with
     * positive {@code taxLiability}.
     */
    @Test
    void annotate_notRetiredYearWithPositiveTaxLiability_stillFoldsBreakdown() {
        var ordinaryFederal = new BigDecimal("3000.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, BigDecimal.ZERO,
                ordinaryFederal, BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var annCtx = new RetirementTaxAnnotator.AnnotationContext(false, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.valueOf(20_000), BigDecimal.ZERO,
                ordinaryFederal, pool, taxStrategy, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal);
    }

    /** Zero/null taxLiability pre-retirement stays a true no-op, same as the retired case. */
    @Test
    void annotate_notRetiredYearWithZeroTaxLiability_staysNoOp() {
        PoolStrategy pool = filingStatusOnlyPool();

        var annCtx = new RetirementTaxAnnotator.AnnotationContext(false, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, pool, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isNull();
    }

    @Test
    void annotate_retiredYearNoLtcgTax_federalTaxUnchangedFromOrdinaryBreakdown() {
        // Zero LTCG tax is a true no-op fold: federalTax stays exactly the ordinary-tax figure,
        // matching pre-fix behavior for retirees with no taxable-pool gains.
        var ordinaryFederal = new BigDecimal("3000.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, BigDecimal.ZERO,
                ordinaryFederal, BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.valueOf(30_000), BigDecimal.ZERO, BigDecimal.ZERO,
                ordinaryFederal, pool, taxStrategy, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal);
    }

    // === T18a-5b: SE tax and the early-withdrawal penalty must also fold into federalTax, or
    // federalTax + stateTax silently falls short of taxLiability whenever either is present ===

    @Test
    void annotate_seTaxYear_foldsIntoFederalTaxComponentAndRestoresIdentity() {
        var ordinaryFederal = new BigDecimal("4000.0000");
        var ordinaryState = new BigDecimal("600.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, ordinaryState,
                ordinaryFederal.add(ordinaryState), BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var seTax = new BigDecimal("2189.4000");
        var totalTaxLiability = ordinaryFederal.add(ordinaryState).add(seTax);
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.valueOf(20_000), BigDecimal.ZERO, BigDecimal.ZERO,
                totalTaxLiability, pool, taxStrategy, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, seTax, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal.add(seTax));
        assertThat(result.stateTax()).isEqualByComparingTo(ordinaryState);
        assertThat(result.federalTax().add(result.stateTax())).isEqualByComparingTo(totalTaxLiability);
    }

    @Test
    void annotate_earlyWithdrawalPenaltyYear_foldsIntoFederalTaxComponentAndRestoresIdentity() {
        var ordinaryFederal = new BigDecimal("1500.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, BigDecimal.ZERO,
                ordinaryFederal, BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class),
                any(BigDecimal.class), any(BigDecimal.class)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var penalty = new BigDecimal("1000.0000");
        var totalTaxLiability = ordinaryFederal.add(penalty);
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.valueOf(10_000), BigDecimal.ZERO, BigDecimal.ZERO,
                totalTaxLiability, pool, taxStrategy, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, penalty, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal.add(penalty));
        assertThat(result.federalTax()).isEqualByComparingTo(totalTaxLiability); // stateTax is null/0 here
    }

    @Test
    void annotate_retiredYear_threadsRealizedLtcgIncomeAndSocialSecurityIntoStateSeam() {
        // Verifies annotate() calls the 5-arg computeDetailedTax with the AnnotationContext's
        // realizedLtcgIncome/federallyTaxedSocialSecurity, not the 3-arg overload -- the seam audit
        // C3 threads the state-base adjustment through (CombinedTaxCalculator picks up these two
        // extra figures; a mock verifies the annotator actually passes them along).
        var ordinaryFederal = new BigDecimal("5000.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, BigDecimal.ZERO,
                ordinaryFederal, BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        var realizedLtcgIncome = new BigDecimal("15000.0000");
        var federallyTaxedSocialSecurity = new BigDecimal("12000.0000");
        BigDecimal totalTaxableIncome = BigDecimal.valueOf(40_000);
        when(taxStrategy.computeDetailedTax(eq(totalTaxableIncome), eq(YEAR), eq(FilingStatus.SINGLE),
                eq(realizedLtcgIncome), eq(federallyTaxedSocialSecurity)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                totalTaxableIncome, BigDecimal.ZERO, BigDecimal.ZERO,
                ordinaryFederal, pool, taxStrategy, BigDecimal.ZERO,
                realizedLtcgIncome, federallyTaxedSocialSecurity, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal);
    }

    // === Audit C1: ordinary-interest income must fold into the recomputed base, not stack on top ===

    @Test
    void annotate_ordinaryInterestIncomePresent_foldsIntoRecomputedTaxableIncomeBase() {
        // Mirrors the LTCG/SS seam test above, but for audit C1's ordinaryInterestIncome: it must
        // land INSIDE totalTaxableIncome (the base the mock's computeDetailedTax stub is keyed on)
        // rather than being ignored or double-added on top -- exactly matching how
        // PoolStrategy.MultiPool#executeWithdrawals folds it into ITS OWN taxableIncome bundle. A
        // wrong wiring here (interest omitted, or the mock invoked with a different base) fails the
        // eq(...) match below and the stub returns Mockito's default CombinedTaxResult, tripping
        // the federalTax assertion.
        var ordinaryFederal = new BigDecimal("6200.0000");
        var ordinaryBreakdown = new CombinedTaxResult(ordinaryFederal, BigDecimal.ZERO,
                ordinaryFederal, BigDecimal.ZERO, BigDecimal.ZERO, false);
        var taxStrategy = mock(TaxCalculationStrategy.class);
        BigDecimal wdFromTraditional = BigDecimal.valueOf(30_000);
        BigDecimal ordinaryInterestIncome = new BigDecimal("1600.0000");
        BigDecimal expectedBase = wdFromTraditional.add(ordinaryInterestIncome); // 31,600
        when(taxStrategy.computeDetailedTax(eq(expectedBase), eq(YEAR), eq(FilingStatus.SINGLE),
                eq(BigDecimal.ZERO), eq(BigDecimal.ZERO)))
                .thenReturn(ordinaryBreakdown);
        PoolStrategy pool = filingStatusOnlyPool();

        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                wdFromTraditional, BigDecimal.ZERO, BigDecimal.ZERO,
                ordinaryFederal, pool, taxStrategy, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, ordinaryInterestIncome);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal);
    }

    // === Wave-4 IRMAA item: irmaa_surcharge / irmaaWarning derivation ===

    @Test
    void annotate_positiveIrmaaSurcharge_setsSurchargeFieldAndWarning() {
        PoolStrategy pool = filingStatusOnlyPool();
        var surcharge = new BigDecimal("2643.6000");
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, pool, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, surcharge,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.irmaaSurcharge()).isEqualByComparingTo(surcharge);
        assertThat(result.irmaaWarning()).isTrue();
    }

    @Test
    void annotate_zeroIrmaaSurcharge_leavesSurchargeAndWarningNull() {
        PoolStrategy pool = filingStatusOnlyPool();
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, pool, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.irmaaSurcharge()).isNull();
        assertThat(result.irmaaWarning()).isNull();
    }
}
