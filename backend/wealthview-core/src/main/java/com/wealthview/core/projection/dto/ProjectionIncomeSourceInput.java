package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

public record ProjectionIncomeSourceInput(
        UUID id,
        String name,
        IncomeSourceType incomeType,
        BigDecimal annualAmount,
        int startAge,
        Integer endAge,
        BigDecimal inflationRate,
        boolean oneTime,
        String taxTreatment,
        BigDecimal annualOperatingExpenses,
        BigDecimal annualMortgageInterest,
        BigDecimal annualMortgagePrincipal,
        BigDecimal annualPropertyTax,
        String depreciationMethod,
        Map<Integer, BigDecimal> depreciationByYear
) {
    /**
     * Whether this income source is active at the given age.
     * One-time sources are only active at their start age.
     * Recurring sources are active from startAge through endAge (inclusive).
     */
    public static boolean isActiveForAge(ProjectionIncomeSourceInput source, int age) {
        if (source.oneTime()) {
            return age == source.startAge();
        }
        if (age < source.startAge()) {
            return false;
        }
        return source.endAge() == null || age <= source.endAge();
    }
}
