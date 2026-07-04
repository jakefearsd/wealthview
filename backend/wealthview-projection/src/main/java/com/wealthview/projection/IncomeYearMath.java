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
 * source's boundary years and its inflation-grown nominal amount. Extracted so
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
     * The source's nominal annual amount inflated to {@code yearsInRetirement}.
     * Inflation compounds from the second retirement year (year index 1), so the
     * first year and any one-time or zero-inflation source pays the base amount.
     */
    static BigDecimal nominalAmount(ProjectionIncomeSourceInput source, int yearsInRetirement) {
        if (source.oneTime() || yearsInRetirement <= 1
                || source.inflationRate().compareTo(BigDecimal.ZERO) == 0) {
            return source.annualAmount();
        }
        return CompoundGrowth.inflate(source.annualAmount(), source.inflationRate(), yearsInRetirement - 1)
                .setScale(SCALE, ROUNDING);
    }
}
