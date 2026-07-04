package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

class IncomeContributionCalculator {

    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    BigDecimal compute(List<ProjectionIncomeSourceInput> sources, int age, int yearsInRetirement) {
        if (sources == null || sources.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (var source : sources) {
            if (ProjectionIncomeSourceInput.isActiveForAge(source, age)) {
                BigDecimal amount = computeAmount(source, yearsInRetirement);
                if (IncomeYearMath.isBoundaryAge(source, age)) {
                    amount = amount.divide(TWO, SCALE, ROUNDING);
                }
                total = total.add(amount);
            }
        }
        return total;
    }

    private BigDecimal computeAmount(ProjectionIncomeSourceInput source, int yearsInRetirement) {
        BigDecimal gross = IncomeYearMath.nominalAmount(source, yearsInRetirement);
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
