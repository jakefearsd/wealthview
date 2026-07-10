package com.wealthview.projection;

import java.math.BigDecimal;

import com.wealthview.core.common.CompoundGrowth;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;

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
     * The source's REAL (today's-dollars) annual amount at {@code yearsInRetirement}. The nominal
     * amount grows by the source's own inflation rate and is then deflated by the scenario inflation
     * rate over the same {@code n = yearsInRetirement - 1} compounding steps, i.e.
     * {@code amount * (1 + sourceInflation)^n / (1 + scenarioInflation)^n}. A COLA source whose rate
     * matches scenario inflation therefore stays constant real, while a fixed-nominal source (source
     * inflation 0) loses purchasing power over time. The first year and any one-time source pays the
     * base amount unchanged.
     */
    static BigDecimal realAmount(ProjectionIncomeSourceInput source, int yearsInRetirement,
                                 BigDecimal scenarioInflationRate) {
        if (source.oneTime() || yearsInRetirement <= 1) {
            return source.annualAmount();
        }
        int steps = yearsInRetirement - 1;
        BigDecimal grown = CompoundGrowth.inflate(source.annualAmount(), source.inflationRate(), steps);
        if (scenarioInflationRate.signum() == 0) {
            return grown.setScale(SCALE, ROUNDING);
        }
        BigDecimal deflator = CompoundGrowth.factor(scenarioInflationRate, steps);
        return grown.divide(deflator, SCALE, ROUNDING);
    }
}
