package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    @Test
    void annotate_retiredYearWithLtcgTax_foldsLtcgIntoFederalTaxComponent() {
        // Ordinary tax on $0 taxable income (no traditional withdrawal/conversion/other income
        // this year) is zero, but the year still owes $5,308.2164 of LTCG tax on a taxable-pool
        // sale -- the bug this fold fixes: federalTax used to stay 0 while taxLiability did not.
        var taxStrategy = mock(TaxCalculationStrategy.class);
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(CombinedTaxResult.ZERO);
        // AGE (65) is >= 63, so annotate() also runs its IRMAA check; stub a zero ceiling so that
        // branch is a no-op and doesn't NPE on an unstubbed mock.
        when(taxStrategy.computeMaxIncomeForTargetRate(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(BigDecimal.ZERO);
        // PoolStrategy is sealed and cannot be mocked; SinglePool.getFilingStatus() always returns
        // SINGLE, which is all annotate() needs from the pool here.
        PoolStrategy pool = new PoolStrategy.SinglePool(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var ltcgTax = new BigDecimal("5308.2164");
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, ltcgTax, pool, taxStrategy, ltcgTax);

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
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(ordinaryBreakdown);
        when(taxStrategy.computeMaxIncomeForTargetRate(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(BigDecimal.ZERO);
        // PoolStrategy is sealed and cannot be mocked; SinglePool.getFilingStatus() always returns
        // SINGLE, which is all annotate() needs from the pool here.
        PoolStrategy pool = new PoolStrategy.SinglePool(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var ltcgTax = new BigDecimal("1964.6018");
        var totalTaxLiability = ordinaryFederal.add(ordinaryState).add(ltcgTax);
        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.valueOf(40_000), BigDecimal.ZERO, BigDecimal.ZERO,
                totalTaxLiability, pool, taxStrategy, ltcgTax);

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
        when(taxStrategy.computeDetailedTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(ordinaryBreakdown);
        when(taxStrategy.computeMaxIncomeForTargetRate(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenReturn(BigDecimal.ZERO);
        // PoolStrategy is sealed and cannot be mocked; SinglePool.getFilingStatus() always returns
        // SINGLE, which is all annotate() needs from the pool here.
        PoolStrategy pool = new PoolStrategy.SinglePool(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        var annCtx = new RetirementTaxAnnotator.AnnotationContext(true, AGE, YEAR,
                BigDecimal.valueOf(30_000), BigDecimal.ZERO, BigDecimal.ZERO,
                ordinaryFederal, pool, taxStrategy, BigDecimal.ZERO);

        var result = annotator.annotate(baseYearDto(), annCtx);

        assertThat(result.federalTax()).isEqualByComparingTo(ordinaryFederal);
    }
}
