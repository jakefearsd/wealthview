package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;

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
     *     takes the same parameter; the two are the accumulation-phase (this class, used only when
     *     {@code !pool.processIncomeSourcesEveryYear()}) and retirement-phase halves of income
     *     processing and must anchor their deflation clock identically.
     */
    BigDecimal compute(List<ProjectionIncomeSourceInput> sources, int age, int yearsFromBase,
                       BigDecimal scenarioInflationRate) {
        return compute(sources, age, yearsFromBase, scenarioInflationRate, 0, null);
    }

    /**
     * Household task 7 (T5-review, spec §1): like the 4-arg overload, but when {@code household} is
     * known, evaluates each source against ITS OWNER's age in {@code taxYear} rather than the
     * uniform {@code age} -- see {@link IncomeYearMath#resolveSourceAge}. {@code household} {@code
     * null} reproduces the 4-arg overload byte-for-byte (every pre-household call site).
     */
    BigDecimal compute(List<ProjectionIncomeSourceInput> sources, int age, int yearsFromBase,
                       BigDecimal scenarioInflationRate, int taxYear, @Nullable HouseholdContext household) {
        if (sources == null || sources.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal total = BigDecimal.ZERO;
        for (var source : sources) {
            int sourceAge = IncomeYearMath.resolveSourceAge(source, age, household, taxYear);
            if (ProjectionIncomeSourceInput.isActiveForAge(source, sourceAge)) {
                BigDecimal amount = computeAmount(source, yearsFromBase, scenarioInflationRate);
                if (IncomeYearMath.isBoundaryAge(source, sourceAge)) {
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
