package com.wealthview.projection;

import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.mortality.MortalityTable;

/**
 * Pure, seeded death-age sampler for stochastic-mortality Monte Carlo trials (sub-project B). Given
 * a person alive at exact age {@code currentAge}, walks ages upward from {@code currentAge} and
 * "rolls the dice" against {@link MortalityTable#qx} at each age; the person dies at the first age
 * where the draw falls below that age's qx. If the walk reaches the table's {@link
 * MortalityTable#maxAge()} without a death draw succeeding, death is forced at {@code maxAge} (every
 * real mortality table's terminal qx is 1.0, so this is a belt-and-suspenders bound rather than a
 * normal outcome).
 *
 * <p>Deterministic given a seeded {@link Random}: callers control reproducibility entirely through
 * the {@code rng} they pass in.
 */
final class MortalitySampler {

    private MortalitySampler() {
    }

    /**
     * Samples the age at which a person alive at exact age {@code currentAge} dies, per {@code
     * table}'s sex-specific one-year mortality probabilities and draws from {@code rng}.
     *
     * @param table      the mortality table to draw qx values from
     * @param sex        {@code "male"}, {@code "female"}, or {@code null}/unrecognized for the
     *                   blended mean (see {@link MortalityTable#qx})
     * @param currentAge the exact age the person is alive at (the walk starts here)
     * @param rng        the seeded random source; the same seed reproduces the same death age
     * @return the sampled death age, in {@code [currentAge, table.maxAge()]}
     */
    static int sampleDeathAge(MortalityTable table, @Nullable String sex, int currentAge, Random rng) {
        int maxAge = table.maxAge();
        for (int age = currentAge; age < maxAge; age++) {
            if (rng.nextDouble() < table.qx(sex, age)) {
                return age;
            }
        }
        return maxAge;
    }
}
