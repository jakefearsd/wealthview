package com.wealthview.projection;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.mortality.MortalityTable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MortalitySamplerTest {

    @Test
    void sampleDeathAge_qxOneAtCurrentAge_diesThisYear() {
        var table = new MortalityTable(Map.of(65, 1.0, 120, 1.0), Map.of(65, 1.0, 120, 1.0));

        assertThat(MortalitySampler.sampleDeathAge(table, "male", 65, new Random(1))).isEqualTo(65);
    }

    @Test
    void sampleDeathAge_zeroUntilTerminal_forcesDeathAtMaxAge() {
        // NOTE: every age from currentAge through maxAge - 1 must be EXPLICITLY tabulated at 0.0.
        // MortalityTable.qx()'s lookup() falls back to 1.0 (not the neighboring tabulated value) for
        // any age that is untabulated but >= the table's minimum tabulated age -- only ages below the
        // minimum fall back to the minimum's qx. A sparse two-point table (e.g. just {60: 0.0, 90:
        // 1.0}) would therefore force death at age 61 (first untabulated age), not walk zero-hazard
        // all the way to maxAge. Verified directly against MortalityTable: qx("female", 61) == 1.0
        // for that sparse map. So this table tabulates every age 60..63 at 0.0, with the terminal age
        // 64 at 1.0, to genuinely exercise the forced-terminal-death fallback path.
        var table = new MortalityTable(Map.of(60, 0.0, 61, 0.0, 62, 0.0, 63, 0.0, 64, 1.0),
                Map.of(60, 0.0, 61, 0.0, 62, 0.0, 63, 0.0, 64, 1.0));

        assertThat(MortalitySampler.sampleDeathAge(table, "female", 60, new Random(42))).isEqualTo(64);
    }

    @Test
    void sampleDeathAge_sameSeed_isReproducible() {
        var table = new MortalityTable(Map.of(60, 0.05, 120, 1.0), Map.of(60, 0.05, 120, 1.0));
        int a = MortalitySampler.sampleDeathAge(table, "male", 60, new Random(7));
        int b = MortalitySampler.sampleDeathAge(table, "male", 60, new Random(7));

        assertThat(a).isEqualTo(b);
    }

    @Test
    void sampleDeathAge_constantHazardOverTenThousandTrials_matchesClosedFormExpectedDeathAge() {
        // Statistical pin: a small hand-built table with a CONSTANT one-year hazard q at every
        // tabulated age from currentAge through maxAge - 1 (every intermediate age is explicitly
        // tabulated -- see the note in sampleDeathAge_zeroUntilTerminal_forcesDeathAtMaxAge above for
        // why a sparse table would not do), with forced death at maxAge if the walk survives that
        // far. This is a truncated geometric distribution, whose mean has a closed form:
        //
        //   Let p = 1 - q (one-year survival probability), n = maxAge - currentAge (years at risk).
        //   Let Y = deathAge - currentAge, supported on {0, 1, ..., n}:
        //     P(Y = k) = p^k * q   for k = 0..n-1  (dies exactly k years after currentAge)
        //     P(Y = n) = p^n                        (survives all n years -> forced death at maxAge)
        //
        //   By the tail-sum identity for a non-negative integer random variable,
        //     E[Y] = sum_{j=1}^{n} P(Y >= j) = sum_{j=1}^{n} p^j = p * (1 - p^n) / (1 - p) = p * (1 - p^n) / q.
        //
        //   So E[deathAge] = currentAge + p * (1 - p^n) / q.
        //
        // Sanity-checked by hand for n=1: E[Y] = 0*q + 1*p = p, and the formula gives
        // p*(1-p)/q = p*q/q = p. Matches. For n=2: E[Y] = 1*p*q + 2*p^2 = pq + 2p^2 = p + p^2 (using
        // q = 1-p), and the formula gives p*(1-p^2)/q = p*(1-p)*(1+p)/q = p*(1+p) = p + p^2. Matches.
        int currentAge = 50;
        int maxAge = 60;
        double q = 0.2;
        double p = 1 - q;
        int n = maxAge - currentAge;
        double expectedMeanDeathAge = currentAge + p * (1 - Math.pow(p, n)) / q;

        Map<Integer, Double> qxByAge = new HashMap<>();
        for (int age = currentAge; age < maxAge; age++) {
            qxByAge.put(age, q);
        }
        qxByAge.put(maxAge, 1.0);
        var table = new MortalityTable(qxByAge, qxByAge);

        int trialCount = 10_000;
        Random rng = new Random(12345);
        long sumOfDeathAges = 0;
        for (int i = 0; i < trialCount; i++) {
            sumOfDeathAges += MortalitySampler.sampleDeathAge(table, "male", currentAge, rng);
        }
        double empiricalMeanDeathAge = (double) sumOfDeathAges / trialCount;

        assertThat(empiricalMeanDeathAge).isCloseTo(expectedMeanDeathAge, within(0.5));
    }
}
