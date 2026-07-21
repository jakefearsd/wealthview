package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.tax.RentalLossCalculator;

/**
 * Projects deterministic per-year retirement income from the configured income sources, including
 * inflation growth, boundary-year proration, rental net-cash-flow handling, and rental-aware
 * taxable-income adjustments (depreciation, passive-loss rules, suspended-loss carryforward).
 * Pure functions extracted from {@link OptimizationContextBuilder}.
 */
final class IncomeProjector {

    private IncomeProjector() {
    }

    /**
     * The shared projection clock and household identity every precompute in this class evaluates
     * against. {@link #computeDeterministic}, {@link #socialSecurityBenefitByYear} and
     * {@link #computeRentalAwareTaxable} produce arrays that are subtracted from / added onto each
     * other (see {@code OptimizationContextBuilder#applySocialSecurityTaxableShare}), so they MUST
     * agree on which years a source is active — building ONE context per income pipeline makes that
     * lockstep structural instead of doc-enforced across three parallel parameter lists.
     *
     * <p>{@code retirementYearOffsetFromBase} is {@code retirementYear - baseYear} (audit C7 /
     * T7-M3): the MC engine only models years from retirement onward, so its year index {@code y}
     * is retirement-anchored. Adding this (possibly negative) offset converts {@code y} into
     * {@code taxYear - baseYear} — the SAME calendar clock {@link IncomeSourceProcessor}/{@link
     * IncomeContributionCalculator} use — before it is floored at 0 and fed to
     * {@link #realGrossForYear}.
     *
     * <p>Household task 8 (T7 gap): a non-null {@code household} evaluates each source's
     * {@code start_age}/{@code end_age} window (and boundary-year 0.5 proration) against ITS
     * OWNER's age in the source's calendar year rather than the uniform retirement-anchored age —
     * the MC-precompute mirror of {@code IncomeSourceProcessor}'s task-7 fix (see
     * {@link IncomeYearMath#resolveSourceAge}). {@code null} household reproduces the age-uniform
     * behavior byte-for-byte; {@code primaryBirthYear} is unused in that case since
     * {@code resolveSourceAge} short-circuits on a null household.
     */
    record Context(int retirementAge, int years, int retirementYearOffsetFromBase,
                   double scenarioInflationRate, int primaryBirthYear,
                   @Nullable HouseholdContext household) {

        /** A single-person clock: uniform retirement-anchored ages, no owner resolution. */
        static Context singlePerson(int retirementAge, int years, int retirementYearOffsetFromBase,
                                    double scenarioInflationRate) {
            return new Context(retirementAge, years, retirementYearOffsetFromBase,
                    scenarioInflationRate, 0, null);
        }
    }

    /**
     * Projects deterministic per-year income (real terms) for the MC engine's full retirement
     * horizon: owner-resolved activation windows, rental net-cash-flow netting, and boundary-year
     * (0.5) proration per source.
     */
    static IncomeYearData[] computeDeterministic(List<ProjectionIncomeSourceInput> sources,
                                                 Context ctx) {
        IncomeYearData[] result = new IncomeYearData[ctx.years()];
        for (int y = 0; y < ctx.years(); y++) {
            result[y] = new IncomeYearData(0, 0);
        }
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        for (int y = 0; y < ctx.years(); y++) {
            var clock = YearClock.forYear(ctx, y);
            double totalIncome = 0;
            double taxableIncome = 0;
            for (var source : sources) {
                var active = activeGross(source, clock, ctx);
                if (active == null) {
                    continue;
                }
                double amount = active.gross();

                // For rental properties, subtract all cash outflows to get net cash flow,
                // matching IncomeSourceProcessor: operating expenses, mortgage interest,
                // property tax, AND mortgage principal (principal reduces available cash even
                // though it is not tax-deductible).
                if (source.incomeType() == IncomeSourceType.RENTAL_PROPERTY) {
                    amount -= nullSafe(source.annualOperatingExpenses());
                    amount -= nullSafe(source.annualMortgageInterest());
                    amount -= nullSafe(source.annualPropertyTax());
                    amount -= nullSafe(source.annualMortgagePrincipal());
                    amount = Math.max(0, amount);
                }

                // Apply boundary multiplier (0.5 at startAge/endAge) for recurring sources only.
                // One-time sources pay their full amount at startAge, matching ICC and ISP.
                if (IncomeYearMath.isBoundaryAge(source, active.sourceAge())) {
                    amount *= 0.5;
                }
                totalIncome += amount;

                // All non-rental income is treated as taxable for MC purposes.
                // Rental net cash is excluded (complex passive-loss rules not applicable here).
                if (source.incomeType() != IncomeSourceType.RENTAL_PROPERTY) {
                    taxableIncome += amount;
                }
            }
            result[y] = new IncomeYearData(totalIncome, taxableIncome);
        }
        return result;
    }

    /**
     * The per-year Social Security benefit (real terms), sharing {@link #activeGross}'s
     * activation/valuation step with {@link #computeDeterministic} — same owner-age windows, same
     * inflation/deflation clock, same boundary-year (0.5) proration — but restricted to
     * {@link IncomeSourceType#SOCIAL_SECURITY} sources. Used by the MC to split the taxable Social
     * Security SHARE out of the naive 100%-taxable figure (audit B2).
     */
    static double[] socialSecurityBenefitByYear(List<ProjectionIncomeSourceInput> sources,
                                                Context ctx) {
        double[] result = new double[ctx.years()];
        if (sources == null || sources.isEmpty()) {
            return result;
        }
        for (int y = 0; y < ctx.years(); y++) {
            var clock = YearClock.forYear(ctx, y);
            double benefit = 0;
            for (var source : sources) {
                if (source.incomeType() != IncomeSourceType.SOCIAL_SECURITY) {
                    continue;
                }
                var active = activeGross(source, clock, ctx);
                if (active == null) {
                    continue;
                }
                double amount = active.gross();
                if (IncomeYearMath.isBoundaryAge(source, active.sourceAge())) {
                    amount *= 0.5;
                }
                benefit += amount;
            }
            result[y] = benefit;
        }
        return result;
    }

    /**
     * Enhances taxableIncomeByYear with rental property effects: depreciation deductions, passive
     * loss rules, and suspended loss carryforward, giving the MC trial withdrawal-tax estimates a
     * more accurate baseline income. Activation windows are owner-resolved against the SAME
     * {@link Context} the base array was built with, so a spouse-owned rental's adjustment lands in
     * the years its gross income was already counted.
     */
    static double[] computeRentalAwareTaxable(double[] baseTaxableIncome,
                                              List<ProjectionIncomeSourceInput> sources,
                                              Context ctx) {
        double[] result = Arrays.copyOf(baseTaxableIncome, ctx.years());
        if (sources == null || sources.isEmpty()) {
            return result;
        }

        var rentalSources = sources.stream()
                .filter(s -> s.incomeType() == IncomeSourceType.RENTAL_PROPERTY)
                .toList();
        if (rentalSources.isEmpty()) {
            return result;
        }

        var calculator = new RentalLossCalculator();
        var suspendedBySource = new HashMap<ProjectionIncomeSourceInput, BigDecimal>();
        for (var source : rentalSources) {
            suspendedBySource.put(source, BigDecimal.ZERO);
        }

        for (int y = 0; y < ctx.years(); y++) {
            var clock = YearClock.forYear(ctx, y);
            double baseOtherIncome = y < baseTaxableIncome.length ? baseTaxableIncome[y] : 0;
            double yearAdjustment = 0;

            for (var source : rentalSources) {
                int sourceAge = IncomeYearMath.resolveSourceAge(
                        source, clock.age(), ctx.household(), clock.calendarYear());
                if (!ProjectionIncomeSourceInput.isActiveForAge(source, sourceAge)) {
                    continue;
                }
                var rentalResult = RentalIncomeHelper.computeForSource(
                        source, y, clock.calendarYear(), baseOtherIncome,
                        suspendedBySource.get(source), calculator);
                suspendedBySource.put(source, rentalResult.newSuspendedLoss());
                yearAdjustment += rentalResult.netTaxableIncome();
            }

            result[y] = Math.max(0, result[y] + yearAdjustment);
        }

        return result;
    }

    /** The three per-year clock values every precompute derives identically from the context. */
    private record YearClock(int age, int calendarYear, int yearsFromBase) {
        static YearClock forYear(Context ctx, int y) {
            int age = ctx.retirementAge() + y;
            return new YearClock(age, ctx.primaryBirthYear() + age,
                    Math.max(0, ctx.retirementYearOffsetFromBase() + y));
        }
    }

    /** A source's owner-resolved age and boundary-unadjusted real gross for one projection year. */
    private record ActiveGross(int sourceAge, double gross) {}

    /**
     * The single activation/valuation step {@link #computeDeterministic} and
     * {@link #socialSecurityBenefitByYear} share: resolve the source's age against its owner,
     * check the activation window, and value the real gross. Returns {@code null} when the source
     * is not active that year (private helper; callers immediately {@code continue}).
     */
    @Nullable
    private static ActiveGross activeGross(ProjectionIncomeSourceInput source, YearClock clock,
                                           Context ctx) {
        int sourceAge = IncomeYearMath.resolveSourceAge(
                source, clock.age(), ctx.household(), clock.calendarYear());
        if (!ProjectionIncomeSourceInput.isActiveForAge(source, sourceAge)) {
            return null;
        }
        return new ActiveGross(sourceAge,
                realGrossForYear(source, clock.yearsFromBase(), ctx.scenarioInflationRate()));
    }

    /**
     * The source's REAL (today's-dollars) gross amount {@code yearsFromBase} calendar years after
     * the projection's base year (audit C7): grown by the source's own inflation, then deflated by
     * scenario inflation over the SAME calendar-anchored exponent (COLA source -> constant real at
     * every calendar year, including across the accumulation/retirement boundary; fixed-nominal
     * source -> eroded). One-time sources pay their face amount unchanged, matching
     * {@link IncomeYearMath#realAmount}.
     */
    private static double realGrossForYear(ProjectionIncomeSourceInput source, int yearsFromBase,
                                           double scenarioInflationRate) {
        double gross = source.annualAmount().doubleValue();
        if (source.oneTime()) {
            return gross;
        }
        if (source.inflationRate() != null && source.inflationRate().compareTo(BigDecimal.ZERO) > 0) {
            gross *= CompoundGrowth.factor(source.inflationRate().doubleValue(), yearsFromBase);
        }
        if (scenarioInflationRate > 0 && yearsFromBase > 0) {
            gross /= CompoundGrowth.factor(scenarioInflationRate, yearsFromBase);
        }
        return gross;
    }

    private static double nullSafe(BigDecimal value) {
        return value != null ? value.doubleValue() : 0.0;
    }
}
