package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.lang.Nullable;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * The per-source, per-year income primitives shared by the deterministic engine
 * ({@link IncomeSourceProcessor}, {@link IncomeContributionCalculator}) and the
 * Monte Carlo projector ({@link IncomeProjector}): the half-year proration at a
 * source's boundary years and its real (today's-dollars) amount. Extracted so
 * these rules live in one place rather than being re-derived in each caller.
 *
 * <p>Active-at-age is not duplicated here — callers use the canonical
 * {@link ProjectionIncomeSourceInput#isActiveForAge}.
 */
final class IncomeYearMath {

    private IncomeYearMath() {
    }

    /**
     * Household task 7 (T5-review, spec §1): the age to evaluate {@code source}'s {@code
     * start_age}/{@code end_age} window (and boundary-year proration) against -- its OWNER's age in
     * {@code year} when {@code household} is a real two-person household and the source is
     * spouse-owned, else the uniform {@code primaryAge}. Owner-agnostic for a single-person context
     * ({@code household == null} or {@code !household.isHousehold()}) and for every primary-owned
     * source, matching every pre-household call site's behavior exactly. Deliberately does NOT
     * distinguish the both-alive phase from the survivor phase: {@link SurvivorIncomeAdjuster}
     * already relabels a surviving deceased-owned source's {@code owner} to the SURVIVOR before this
     * method ever sees it (spec §4.1), so a single owner-lookup rule is correct in both phases -- see
     * that class's Javadoc.
     */
    static int resolveSourceAge(ProjectionIncomeSourceInput source, int primaryAge,
                                @Nullable HouseholdContext household, int year) {
        if (household == null || !household.isHousehold() || !"spouse".equals(source.owner())) {
            return primaryAge;
        }
        return Objects.requireNonNull(household.spouse(), "No spouse in this household context").ageIn(year);
    }

    /**
     * A recurring source is prorated to half in its first and last active year
     * (mid-year start/stop). One-time sources are never boundary-prorated. Callers
     * apply the actual 0.5 factor themselves so each keeps its own numeric type and
     * rounding.
     */
    static boolean isBoundaryAge(ProjectionIncomeSourceInput source, int age) {
        return !source.oneTime()
                && (age == source.startAge()
                        || (source.endAge() != null && age == source.endAge()));
    }

    /**
     * The source's REAL (today's-dollars) annual amount at {@code yearsFromBase} — a 1-INDEXED
     * count of calendar years since the projection's base year (audit C7: {@code (taxYear -
     * baseYear) + 1}, so the base year itself is 1, one calendar year later is 2, etc. — NOT years
     * since retirement; same 1-indexed shape the pre-C7 {@code yearsInRetirement} parameter used,
     * just re-anchored). The nominal amount grows by the source's own inflation rate and is then
     * deflated by the scenario inflation rate over the same {@code n = yearsFromBase - 1}
     * compounding steps, i.e. {@code amount * (1 + sourceInflation)^n / (1 + scenarioInflation)^n}.
     * A COLA source whose rate matches scenario inflation therefore stays EXACTLY constant real
     * (equal to its face {@code annualAmount}) at every calendar year, including across the
     * accumulation/retirement boundary, because growth and deflation share one calendar-anchored
     * clock. A fixed-nominal source (source inflation 0) loses purchasing power over time —
     * including during accumulation, where the prior retirement-anchored clock stayed pinned at
     * {@code yearsInRetirement <= 1} (steps = 0) regardless of elapsed calendar time (audit C7's
     * bug: up to ~45% overstatement at a 15-year accumulation boundary). The base year itself
     * ({@code yearsFromBase <= 1}) and any one-time source pay the base amount unchanged.
     */
    static BigDecimal realAmount(ProjectionIncomeSourceInput source, int yearsFromBase,
                                 BigDecimal scenarioInflationRate) {
        if (source.oneTime() || yearsFromBase <= 1) {
            return source.annualAmount();
        }
        int steps = yearsFromBase - 1;
        BigDecimal grown = CompoundGrowth.inflate(source.annualAmount(), source.inflationRate(), steps);
        if (scenarioInflationRate.signum() == 0) {
            return grown.setScale(SCALE, ROUNDING);
        }
        BigDecimal deflator = CompoundGrowth.factor(scenarioInflationRate, steps);
        return grown.divide(deflator, SCALE, ROUNDING);
    }
}
