package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

class IncomeContributionCalculator {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /**
     * Sums each active source's real (today's-dollars) amount for one accumulation-phase year.
     *
     * @param yearsFromBase a 1-INDEXED count of calendar years since the projection's base year
     *     (audit C7: {@code (taxYear - baseYear) + 1}, floored by the caller so the base year and
     *     any earlier year are 1) — NOT years since retirement. {@link IncomeSourceProcessor#process}
     *     takes the same parameter; the two are the accumulation-phase (this class, SinglePool only)
     *     and retirement-phase halves of income processing and must anchor their deflation clock
     *     identically.
     */
    BigDecimal compute(List<ProjectionIncomeSourceInput> sources, int age, int yearsFromBase,
                       BigDecimal scenarioInflationRate) {
        if (sources == null || sources.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (var source : sources) {
            if (ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                BigDecimal amount = computeAmount(source, yearsFromBase, scenarioInflationRate);
                if (IncomeYearMath.isBoundaryAge(source, age)) {
                    amount = amount.divide(TWO, SCALE, ROUNDING);
                }
                total = total.add(amount);
            }
        }
        return total;
    }

    private BigDecimal computeAmount(ProjectionIncomeSourceInput source, int yearsFromBase,
                                     BigDecimal scenarioInflationRate) {
        BigDecimal gross = IncomeYearMath.realAmount(source, yearsFromBase, scenarioInflationRate);
        if (source.incomeType() == IncomeSourceType.RENTAL_PROPERTY) {
            gross = gross.subtract(sumExpenses(source));
        }
        return gross;
    }

    private BigDecimal sumExpenses(ProjectionIncomeSourceInput source) {
        BigDecimal total = BigDecimal.ZERO;
        if (source.annualOperatingExpenses() != null) {
            total = total.add(source.annualOperatingExpenses());
        }
        if (source.annualMortgageInterest() != null) {
            total = total.add(source.annualMortgageInterest());
        }
        if (source.annualMortgagePrincipal() != null) {
            total = total.add(source.annualMortgagePrincipal());
        }
        if (source.annualPropertyTax() != null) {
            total = total.add(source.annualPropertyTax());
        }
        return total;
    }
}
