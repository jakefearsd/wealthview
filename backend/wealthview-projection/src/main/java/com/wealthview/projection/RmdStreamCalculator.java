package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.PersonId;

/**
 * Household task 4: computes and physically forces the year's Required Minimum Distribution as ONE
 * stream per owner. Each owner's stream uses that owner's own SECURE-2.0 start age
 * ({@link RmdCalculator#rmdStartAge}) and Uniform-Lifetime distribution period
 * ({@link RmdCalculator#distributionPeriod}) against that owner's own prior-year-end traditional
 * balance, so an age-gap couple runs two independent streams and neither drives the other's IRA. A
 * single-person scenario has exactly one (PRIMARY) pool entry, so this reduces to the pre-household
 * single-stream computation bit-for-bit. Extracted from {@link DeterministicProjectionEngine} to keep
 * the engine's per-year orchestration lean.
 */
final class RmdStreamCalculator {

    /** Scale/rounding for the RMD divide, matching the pre-household single-stream computation. */
    private static final int RMD_SCALE = 4;

    /**
     * The year's two RMD figures: {@code requested} is the summed RMD the streams computed (the DTO's
     * {@code rmd_amount}); {@code forced} the summed amount actually distributed after per-owner
     * balance capping (threaded into the tax attribution).
     */
    record RmdStreams(BigDecimal requested, BigDecimal forced) {}

    private RmdStreamCalculator() {}

    /**
     * Computes each owner's RMD and physically forces it out of that owner's traditional pool (into a
     * fresh at-cost taxable lot), right after growth and before any Roth conversion (IRS ordering).
     *
     * @param household the resolved household context ({@code null} or single-person ⇒ PRIMARY only)
     * @param primaryBirthYear the primary member's birth year (the engine's resolved birth year)
     * @param year the calendar year being projected
     * @param priorTraditionalByOwner each owner's traditional balance snapshotted BEFORE growth
     * @param pool the pool to force distributions out of, per owner
     */
    static RmdStreams computeAndForce(@Nullable HouseholdContext household, int primaryBirthYear, int year,
                                      Map<PersonId, BigDecimal> priorTraditionalByOwner, PoolStrategy pool) {
        BigDecimal requested = BigDecimal.ZERO;
        BigDecimal forced = BigDecimal.ZERO;
        for (var entry : priorTraditionalByOwner.entrySet()) {
            PersonId owner = entry.getKey();
            if (owner == PersonId.SPOUSE && (household == null || !household.isHousehold())) {
                continue;
            }
            int ownerBirthYear = birthYearFor(household, primaryBirthYear, owner);
            int ownerAge = year - ownerBirthYear;
            if (ownerAge < RmdCalculator.rmdStartAge(ownerBirthYear)) {
                continue;
            }
            double divisor = RmdCalculator.distributionPeriod(ownerAge);
            if (divisor <= 0) {
                continue;
            }
            BigDecimal ownerRmd = entry.getValue()
                    .divide(BigDecimal.valueOf(divisor), RMD_SCALE, RoundingMode.HALF_UP);
            requested = requested.add(ownerRmd);
            forced = forced.add(pool.forceRmd(owner, ownerRmd));
        }
        return new RmdStreams(requested, forced);
    }

    /**
     * The birth year governing an owner's RMD stream: the spouse's own for a {@link PersonId#SPOUSE}
     * pool in a two-person household, the primary's otherwise. Reads the nullable spouse exactly once.
     */
    private static int birthYearFor(@Nullable HouseholdContext household, int primaryBirthYear, PersonId owner) {
        if (owner == PersonId.SPOUSE && household != null) {
            HouseholdContext.Person spouse = household.spouse();
            if (spouse != null) {
                return spouse.birthYear();
            }
        }
        return primaryBirthYear;
    }
}
