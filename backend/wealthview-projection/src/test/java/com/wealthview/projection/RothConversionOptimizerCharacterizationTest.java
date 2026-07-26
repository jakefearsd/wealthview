package com.wealthview.projection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.projection.testutil.FlatTaxStubs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Golden-master characterization test for {@link RothConversionOptimizer}.
 *
 * <p>The optimizer is fully deterministic — it uses a grid scan plus ternary refinement
 * over the conversion fraction, with no random number generation anywhere in the class
 * (verified by inspection). So no seed seam is required: the same inputs always yield
 * the same schedule.
 *
 * <p>The fixture is a single-filer with a large traditional IRA and a flat-20% tax model
 * (matching the sibling {@code RothConversionOptimizerTest} stub). The projection runs in REAL
 * (today's-dollars) terms: the portfolio grows at the real return assumption and spending is
 * constant real, so the captured schedule still shows a genuine Roth-conversion benefit — lifetime
 * tax falls from $339,016 to $293,276 — while the slower, constant-real drawdown leaves a small
 * traditional balance at the end age. These assertions are the contract the decomposition must preserve.
 *
 * <p><strong>Audit C4 golden regen (2026-07-12) — attribution: this fixture's movement is 100% the
 * RMD-proceeds CREDIT; the frame fix contributes nothing here.</strong> The golden moved when
 * {@code ConversionSimulator.applyRmds} started crediting the after-tax RMD remainder to the
 * taxable pool instead of letting it vanish; the {@code returnMean} FRAME fix does not touch this
 * fixture — {@code .assumptions(0.06, ...)} is an explicit real rate passed directly to the
 * builder, bypassing {@code OptimizationContextBuilder#resolveReturnMean} entirely. (The mirror
 * pin — frame fix isolated, credit structurally $0 — is
 * {@code RothConversionAuditC4BiasDirectionTest}; together they give each C4 fix exactly one
 * isolating regression pin.) The RMD credit swells the
 * baseline (no-conversion) arm's spendable taxable balance from RMD age onward, which lowers
 * {@code lifetimeTaxWithout} — so the tax-minimizing search now finds a SMALLER optimal conversion
 * fraction (1.0 -&gt; 0.6898) than before: converting is worth less when the alternative
 * (letting RMDs happen and keeping the proceeds) got cheaper. Every changed literal below was
 * independently reconciled by hand: e.g. year 0, {@code traditionalBalance = 900000*1.06 -
 * conversionByYear[0]}; {@code taxableBalance = 150000*1.06 - conversionTaxByYear[0] -
 * essentialFloor(40000)}; {@code rothBalance = 50000*1.06 + conversionByYear[0]} -- all match the
 * dumped figures to the cent. {@code targetTraditionalBalance} is untouched (a pure function of the
 * static RMD-target-bracket config, independent of both the frame and the RMD credit).
 */
class RothConversionOptimizerCharacterizationTest {

    private FederalTaxCalculator taxCalculator;

    @BeforeEach
    void setUp() {
        // Flat 20% tax + the shared bracket-ceiling table -- see FlatTaxStubs javadoc for which
        // call sites this exact (unscaled) shape matches. This golden fixture's pinned values
        // (see class javadoc) depend on this EXACT arithmetic -- do not swap shapes.
        taxCalculator = FlatTaxStubs.flat20();
        FlatTaxStubs.stubBracketCeilings(taxCalculator);
    }

    private static final double TOL = 1e-6;

    /** Single filer, $900K traditional IRA, retirement at 62, end age 90. */
    private RothConversionOptimizer goldenOptimizer() {
        return new RothConversionOptimizer.Builder()
                .portfolio(900_000, 50_000, 150_000)
                .income(new double[28], new double[28])
                .demographics(1963, 62, 90)
                .taxConfig(0.22, 0.12, 0.10, FilingStatus.SINGLE, taxCalculator)
                .assumptions(0.06, 40_000, 5, "taxable_first")
                .build();
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsScheduleSummary() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()).hasSize(28);
        assertThat(schedule.conversionFraction()).isEqualTo(0.6897513152348189, offset(TOL));
        assertThat(schedule.lifetimeTaxWith()).isEqualTo(293275.59829058056, offset(1e-3));
        assertThat(schedule.lifetimeTaxWithout()).isEqualTo(339016.0299771314, offset(1e-3));
        assertThat(schedule.exhaustionAge()).isEqualTo(90);
        // Real terms: constant-real (lower) spending draws down traditional more slowly, so it is not
        // fully exhausted to the target by the end age (see pinsFinalYear: $664,264 remains).
        assertThat(schedule.exhaustionTargetMet()).isFalse();
        assertThat(schedule.targetTraditionalBalance()).isEqualTo(1217700.0, offset(1e-3));
    }

    @Test
    void optimize_largeTraditionalIraScenario_conversionsLowerLifetimeTax() {
        // Sanity invariant: the optimizer only converts when it reduces lifetime tax.
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.lifetimeTaxWith()).isLessThan(schedule.lifetimeTaxWithout());
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsFirstConversionYear() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()[0]).isEqualTo(68975.13152348189, offset(1e-3));
        assertThat(schedule.conversionTaxByYear()[0]).isEqualTo(13795.026304696377, offset(1e-3));
        assertThat(schedule.traditionalBalance()[0]).isEqualTo(885024.8684765181, offset(1e-3));
        assertThat(schedule.rothBalance()[0]).isEqualTo(121975.13152348189, offset(1e-3));
        assertThat(schedule.taxableBalance()[0]).isEqualTo(105204.97369530363, offset(1e-3));
        assertThat(schedule.projectedRmd()[0]).isEqualTo(0.0, offset(TOL));
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsSecondConversionYear() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()[1]).isEqualTo(68975.13152348189, offset(1e-3));
        assertThat(schedule.traditionalBalance()[1]).isEqualTo(869151.2290616273, offset(1e-3));
        assertThat(schedule.rothBalance()[1]).isEqualTo(198268.7709383727, offset(1e-3));
        assertThat(schedule.taxableBalance()[1]).isEqualTo(57722.24581232548, offset(1e-3));
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsRmdYear() {
        var schedule = goldenOptimizer().optimize();

        // Index 13 = age 75, first year an RMD applies for a 1963 birth year.
        assertThat(schedule.conversionByYear()[13]).isEqualTo(0.0, offset(TOL));
        assertThat(schedule.traditionalBalance()[13]).isEqualTo(739042.8755032691, offset(1e-3));
        assertThat(schedule.rothBalance()[13]).isEqualTo(769947.1029003205, offset(1e-3));
        assertThat(schedule.projectedRmd()[13]).isEqualTo(30106.773670709117, offset(1e-3));
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsFinalYear() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()[27]).isEqualTo(0.0, offset(TOL));
        assertThat(schedule.traditionalBalance()[27]).isEqualTo(664263.804110728, offset(1e-3));
        assertThat(schedule.rothBalance()[27]).isEqualTo(1740776.4506689948, offset(1e-3));
        assertThat(schedule.taxableBalance()[27]).isEqualTo(1929.7811737717057, offset(1e-3));
    }

    @Test
    void optimize_sameInputs_producesIdenticalScheduleAcrossRuns() {
        // The optimizer has no random component; identical inputs must yield an
        // identical schedule, which is what makes this a valid refactor gate.
        var a = goldenOptimizer().optimize();
        var b = goldenOptimizer().optimize();

        assertThat(a.lifetimeTaxWith()).isEqualTo(b.lifetimeTaxWith());
        assertThat(a.conversionByYear()).containsExactly(b.conversionByYear());
        assertThat(a.traditionalBalance()).containsExactly(b.traditionalBalance());
        assertThat(a.rothBalance()).containsExactly(b.rothBalance());
    }
}
