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
        var config = new PoolStrategy.PoolConfig(FilingStatus.SINGLE, BigDecimal.ZERO, BigDecimal.ZERO,
                "fixed", null, null, WithdrawalOrder.TAXABLE_FIRST, null, null);
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
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

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
                BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal.add(ltcgTax));
        assertThat(result.stateTax()).isEqualByComparingTo(ordinaryState);
        // The whole point of the fold: federalTax + stateTax now reconciles with the year's total
        // taxLiability (the value the engine passed in), instead of silently dropping the LTCG slice.
        assertThat(result.federalTax().add(result.stateTax())).isEqualByComparingTo(totalTaxLiability);
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
                BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal);
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
                realizedLtcgIncome, federallyTaxedSocialSecurity, BigDecimal.ZERO);

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
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, surcharge);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.irmaaSurcharge()).isEqualByComparingTo(surcharge);
        assertThat(result.irmaaWarning()).isTrue();
    }

    @Test
    void annotate_zeroIrmaaSurcharge_leavesSurchargeAndWarningNull() {
        PoolStrategy pool = filingStatusOnlyPool();
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, pool, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.irmaaSurcharge()).isNull();
        assertThat(result.irmaaWarning()).isNull();
    }
}
