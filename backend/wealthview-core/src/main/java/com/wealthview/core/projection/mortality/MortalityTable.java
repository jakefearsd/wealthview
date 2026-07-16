package com.wealthview.core.projection.mortality;

import java.util.Map;
import java.util.Objects;

import org.springframework.lang.Nullable;

/**
 * Immutable SSA period-life mortality table: probability of death within one year ({@code qx}),
 * given alive at exact age, keyed by sex. Used by the stochastic-mortality Monte Carlo sampler
 * (sub-project B) to draw death ages. See {@code MortalityRateEntity} / migration V080 for the
 * seeded reference data this wraps.
 *
 * @param maleQx   male qx by exact age
 * @param femaleQx female qx by exact age
 */
public record MortalityTable(Map<Integer, Double> maleQx, Map<Integer, Double> femaleQx) {

    public MortalityTable {
        Objects.requireNonNull(maleQx, "maleQx must not be null");
        Objects.requireNonNull(femaleQx, "femaleQx must not be null");
    }

    /**
     * qx for the given sex at exact {@code age}. {@code sex} {@code null} or unrecognized (i.e.
     * anything other than {@code "male"}/{@code "female"}) returns the blended mean of the male
     * and female qx. Ages above {@link #maxAge()} force death (returns {@code 1.0}); ages below
     * the table's minimum tabulated age return that minimum age's qx.
     */
    // ShortMethodName: 'qx' is the standard actuarial notation for probability of death within
    // one year given alive at exact age -- the same name as the mortality_rates.qx column.
    @SuppressWarnings("PMD.ShortMethodName")
    public double qx(@Nullable String sex, int age) {
        if (age > maxAge()) {
            return 1.0;
        }
        if ("male".equals(sex)) {
            return lookup(maleQx, age);
        }
        if ("female".equals(sex)) {
            return lookup(femaleQx, age);
        }
        return (lookup(maleQx, age) + lookup(femaleQx, age)) / 2.0;
    }

    /** Highest tabulated age (terminal); used to bound the sampling walk. */
    public int maxAge() {
        return Math.max(maleQx.keySet().stream().max(Integer::compareTo).orElse(0),
                femaleQx.keySet().stream().max(Integer::compareTo).orElse(0));
    }

    private static double lookup(Map<Integer, Double> qx, int age) {
        Double v = qx.get(age);
        if (v != null) {
            return v;
        }
        int min = qx.keySet().stream().min(Integer::compareTo).orElseThrow();
        return age < min ? qx.get(min) : 1.0;
    }
}
