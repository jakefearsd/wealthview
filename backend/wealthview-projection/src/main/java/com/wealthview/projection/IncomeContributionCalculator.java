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
            if (isActiveAtAge(source, age)) {
                total = total.add(computeAmount(source, yearsInRetirement));
            }
        }
        return total;
    }

    private boolean isActiveAtAge(ProjectionIncomeSourceInput source, int age) {
        if (age < source.startAge()) {
            return false;
        }
        return source.endAge() == null || age < source.endAge();
    }

    private BigDecimal computeAmount(ProjectionIncomeSourceInput source, int yearsInRetirement) {
        if (source.oneTime() || yearsInRetirement <= 1
                || source.inflationRate().compareTo(BigDecimal.ZERO) == 0) {
            return source.annualAmount();
        }
        BigDecimal factor = BigDecimal.ONE.add(source.inflationRate())
                .pow(yearsInRetirement - 1);
        return source.annualAmount().multiply(factor).setScale(SCALE, ROUNDING);
    }
}
