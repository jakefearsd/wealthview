package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

class IncomeContributionCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal TWO = new BigDecimal("2");

    BigDecimal compute(List<ProjectionIncomeSourceInput> sources, int age, int yearsInRetirement) {
        if (sources == null || sources.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (var source : sources) {
            if (isActiveAtAge(source, age)) {
                BigDecimal amount = computeAmount(source, yearsInRetirement);
                if (!source.oneTime() && isBoundaryAge(source, age)) {
                    amount = amount.divide(TWO, SCALE, ROUNDING);
                }
                total = total.add(amount);
            }
        }
        return total;
    }

    private boolean isActiveAtAge(ProjectionIncomeSourceInput source, int age) {
        if (source.oneTime()) {
            return age == source.startAge();
        }
        if (age < source.startAge()) {
            return false;
        }
        return source.endAge() == null || age <= source.endAge();
    }

    private boolean isBoundaryAge(ProjectionIncomeSourceInput source, int age) {
        return age == source.startAge()
                || (source.endAge() != null && age == source.endAge());
    }

    private BigDecimal computeAmount(ProjectionIncomeSourceInput source, int yearsInRetirement) {
        BigDecimal gross;
        if (source.oneTime() || yearsInRetirement <= 1
                || source.inflationRate().compareTo(BigDecimal.ZERO) == 0) {
            gross = source.annualAmount();
        } else {
            BigDecimal factor = BigDecimal.ONE.add(source.inflationRate())
                    .pow(yearsInRetirement - 1);
            gross = source.annualAmount().multiply(factor).setScale(SCALE, ROUNDING);
        }
        if ("rental_property".equals(source.incomeType())) {
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
