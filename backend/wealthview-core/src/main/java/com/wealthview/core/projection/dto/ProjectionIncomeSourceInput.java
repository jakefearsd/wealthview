package com.wealthview.core.projection.dto;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The engine-facing (fully resolved) shape of one scenario-linked income source.
 *
 * @param mortgageInterestByYear for a rental linked to a property with loan details, the NOMINAL
 *              mortgage interest paid in each calendar year per the amortization schedule (the
 *              start and payoff years count only their own payments; no entry after payoff).
 *              Keyed like {@code depreciationByYear}; the engines convert each year's amount to
 *              real terms as a fixed-nominal outflow. {@code null} when there is no amortizable
 *              mortgage.
 * @param mortgagePrincipalByYear the matching NOMINAL principal repaid each calendar year (a cash
 *              outflow that is not tax-deductible). {@code null} when there is no amortizable
 *              mortgage.
 * @param owner Household/survivor modeling (sub-project A): {@code "primary"} or {@code "spouse"}.
 *              Every existing constructor defaults this to {@code "primary"}.
 * @param survivorPercent Fraction of this source the survivor keeps from the first-death
 *              transition year forward (ignored for SS-typed sources, which use the statutory
 *              keep-larger rule instead). Every existing constructor defaults this to
 *              {@code BigDecimal.ONE} (full continuation), reproducing pre-household behavior
 *              byte-for-byte.
 */
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
        Map<Integer, BigDecimal> mortgageInterestByYear,
        Map<Integer, BigDecimal> mortgagePrincipalByYear,
        BigDecimal annualPropertyTax,
        String depreciationMethod,
        Map<Integer, BigDecimal> depreciationByYear,
        String owner,
        BigDecimal survivorPercent
) {

    /**
     * Back-compat convenience for call sites predating {@code owner}/{@code survivorPercent}
     * (household modeling): defaults owner to {@code "primary"} and survivorPercent to
     * {@code BigDecimal.ONE} (full continuation), reproducing pre-household behavior
     * byte-for-byte.
     */
    public ProjectionIncomeSourceInput(
            UUID id, String name, IncomeSourceType incomeType, BigDecimal annualAmount,
            int startAge, Integer endAge, BigDecimal inflationRate, boolean oneTime, String taxTreatment,
            BigDecimal annualOperatingExpenses, Map<Integer, BigDecimal> mortgageInterestByYear,
            Map<Integer, BigDecimal> mortgagePrincipalByYear, BigDecimal annualPropertyTax,
            String depreciationMethod, Map<Integer, BigDecimal> depreciationByYear) {
        this(id, name, incomeType, annualAmount, startAge, endAge, inflationRate, oneTime, taxTreatment,
                annualOperatingExpenses, mortgageInterestByYear, mortgagePrincipalByYear, annualPropertyTax,
                depreciationMethod, depreciationByYear, "primary", BigDecimal.ONE);
    }

    /** The NOMINAL mortgage interest paid in {@code calendarYear}; zero when none is scheduled. */
    public BigDecimal mortgageInterestIn(int calendarYear) {
        return scheduledIn(mortgageInterestByYear, calendarYear);
    }

    /** The NOMINAL mortgage principal repaid in {@code calendarYear}; zero when none is scheduled. */
    public BigDecimal mortgagePrincipalIn(int calendarYear) {
        return scheduledIn(mortgagePrincipalByYear, calendarYear);
    }

    private static BigDecimal scheduledIn(Map<Integer, BigDecimal> schedule, int calendarYear) {
        return schedule == null ? BigDecimal.ZERO : schedule.getOrDefault(calendarYear, BigDecimal.ZERO);
    }

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
