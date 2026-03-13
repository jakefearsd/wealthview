package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

class IncomeContributionCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    BigDecimal compute(List<ProjectionIncomeSourceInput> sources, int age, int yearsInRetirement) {
        if (sources == null || sources.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (var source : sources) {
            if (age < source.startAge()) {
                continue;
            }
            if (source.endAge() != null && age >= source.endAge()) {
                continue;
            }

            BigDecimal amount;
            if (source.oneTime() || yearsInRetirement <= 1
                    || source.inflationRate().compareTo(BigDecimal.ZERO) == 0) {
                amount = source.annualAmount();
            } else {
                BigDecimal factor = BigDecimal.ONE.add(source.inflationRate())
                        .pow(yearsInRetirement - 1);
                amount = source.annualAmount().multiply(factor).setScale(SCALE, ROUNDING);
            }
            total = total.add(amount);
        }
        return total;
    }
}
