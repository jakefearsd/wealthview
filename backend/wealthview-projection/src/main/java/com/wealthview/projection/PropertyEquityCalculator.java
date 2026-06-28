package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.property.AmortizationCalculator;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Computes projected real-estate equity for a projection year — appreciated property value less
 * the amortized mortgage balance — and folds it into the year's net-worth fields. Pure functions
 * extracted from {@link DeterministicProjectionEngine}.
 */
final class PropertyEquityCalculator {

    private PropertyEquityCalculator() {
    }

    /** Total equity across all properties at {@code yearsElapsed}, or {@code null} when there are none. */
    @Nullable
    static BigDecimal compute(List<ProjectionPropertyInput> properties, int yearsElapsed) {
        if (properties.isEmpty()) {
            return null;
        }
        BigDecimal totalEquity = BigDecimal.ZERO;
        for (var prop : properties) {
            BigDecimal appreciationFactor = BigDecimal.ONE.add(prop.annualAppreciationRate())
                    .pow(yearsElapsed);
            BigDecimal projectedValue = prop.currentValue()
                    .multiply(appreciationFactor)
                    .setScale(SCALE, ROUNDING);

            BigDecimal mortgageBalance;
            if (prop.loanAmount() != null && prop.annualInterestRate() != null
                    && prop.loanTermMonths() > 0 && prop.loanStartDate() != null) {
                // Amortize from the start of the projection to projection year
                LocalDate asOf = prop.loanStartDate()
                        .plusYears(yearsElapsed)
                        .withDayOfYear(1);
                mortgageBalance = AmortizationCalculator
                        .remainingBalance(prop.loanAmount(), prop.annualInterestRate(),
                                prop.loanTermMonths(), prop.loanStartDate(), asOf)
                        .max(BigDecimal.ZERO);
            } else {
                mortgageBalance = prop.mortgageBalance() != null ? prop.mortgageBalance() : BigDecimal.ZERO;
            }

            totalEquity = totalEquity.add(projectedValue.subtract(mortgageBalance));
        }
        return totalEquity;
    }

    /** Adds property equity and combined net worth to {@code base}; no-op when equity is null. */
    static ProjectionYearDto apply(ProjectionYearDto base, @Nullable BigDecimal propertyEquity) {
        if (propertyEquity == null) {
            return base;
        }
        BigDecimal totalNetWorth = base.endBalance().add(propertyEquity);
        return base.withPropertyEquity(propertyEquity, totalNetWorth);
    }
}
