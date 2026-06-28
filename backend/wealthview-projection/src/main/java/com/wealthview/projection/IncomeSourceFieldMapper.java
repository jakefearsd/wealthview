package com.wealthview.projection;

import java.math.BigDecimal;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionYearDto;

/**
 * Maps the per-year {@link IncomeSourceProcessor.IncomeSourceYearResult} onto a
 * {@link ProjectionYearDto}, normalizing zero amounts to null and preserving existing totals when
 * no income-source data applies. Extracted from {@link DeterministicProjectionEngine}.
 */
final class IncomeSourceFieldMapper {

    private IncomeSourceFieldMapper() {
    }

    static ProjectionYearDto apply(ProjectionYearDto base,
                                   @Nullable IncomeSourceProcessor.IncomeSourceYearResult isResult) {
        if (isResult == null) {
            return base;
        }

        BigDecimal totalIncome = isResult.totalCashInflow();
        var incomeBySource = isResult.incomeBySource().isEmpty() ? null : isResult.incomeBySource();
        var rentalDetails = isResult.rentalPropertyDetails().isEmpty()
                ? null : isResult.rentalPropertyDetails();

        return base.withIncomeSourceFields(
                positiveOrDefault(totalIncome, base.incomeStreamsTotal()),
                nullIfZero(isResult.rentalIncomeGross()),
                nullIfZero(isResult.rentalExpensesTotal()),
                nullIfZero(isResult.depreciationTotal()),
                nullIfZero(isResult.rentalLossApplied()),
                nullIfZero(isResult.suspendedLossCarryforward()),
                nullIfZero(isResult.socialSecurityTaxable()),
                nullIfZero(isResult.selfEmploymentTax()),
                incomeBySource,
                rentalDetails);
    }

    @Nullable
    private static BigDecimal nullIfZero(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : null;
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value.compareTo(BigDecimal.ZERO) > 0 ? value : fallback;
    }
}
