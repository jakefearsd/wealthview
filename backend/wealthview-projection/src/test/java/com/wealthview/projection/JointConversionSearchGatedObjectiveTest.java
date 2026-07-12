package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.tax.BracketPoint;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T26: {@link JointConversionSearch}'s conversion-arm scoring honors the profile's
 * {@code gate_on_adaptive_rules} toggle, so conversion schedules are chosen under the SAME gated
 * objective the downstream discretionary search certifies against — closing the T24 follow-up gap
 * where conversions were optimized for the no-adaptation objective while the spending search gated
 * with-rules (conservative, not the joint optimum).
 *
 * <p><strong>Constructed divergence fixture.</strong> A steeply progressive tax mock
 * (10%/22%/32%/50% at 30k/90k/150k), {@code roth_first} withdrawal order, a small taxable pool
 * ($60k) against a large traditional pool ($1.4M), a $55k essential floor at 90% confidence, and a
 * generous 40% adjustment rate. Conversions (fill-to-32%-bracket, so up to $150k/yr) are genuinely
 * beneficial on median paths (they route retirement spending through tax-free Roth withdrawals
 * instead of 32-50%-bracket traditional draws) but hurt the worst-return tail early (conversion tax
 * drains the small taxable pool while draws stack ON TOP of conversion income into the 50%
 * bracket). The no-adaptation gate binds on that unrescued tail and picks a LIGHTER conversion
 * schedule (total $836,217); the with-rules gate — the profile's actual certification metric —
 * rescues those tail paths by cutting discretionary spending in-simulation, so the joint search
 * can afford the HEAVIER schedule (total $1,028,364) whose median-path tax benefit then unlocks
 * strictly more certified spending ($91,810 vs the old pipeline's $91,365).
 *
 * <p><strong>Pre-T26 baseline capture.</strong> The {@code PRE_T26_*} constants were captured by
 * running this exact fixture against the pre-T26 {@code JointConversionSearch} (arm scoring
 * hard-wired to {@code (false, 0.0)}) via a temporary stash of only that file — standard golden
 * discipline (see {@code RothConversionAuditC4BiasDirectionTest}). The same run confirmed the
 * toggle-OFF pipeline is byte-identical before/after T26 (identical schedule totals, recommended
 * spending, and aggregate statistics to the last printed decimal), which is what
 * {@link #optimize_toggleOff_pinsPreT26IdenticalBaseline} pins permanently.
 *
 * <p><strong>Two-stage bias note.</strong> Arm selection runs on the independent 500-path arm set
 * (seed+1); final numbers come from the main set (seed). The "joint optimum never worse" guarantee
 * is exact on the ARM set (a max over correctly-scored arms). On the main set it holds for this
 * pinned fixture (+445.35) but is not a cross-path-set theorem — small negative main-set deltas
 * were observed on other seeds during fixture construction (documented in the T26 report), which
 * is inherent to the (deliberate, T24-reviewed) two-stage bias-control design, not a T26 defect.
 */
class JointConversionSearchGatedObjectiveTest {

    private static final long SEED = 10L;

    /** Old pipeline (pre-T26), gate ON: arms scored no-adapt, discretionary search gated. */
    private static final String PRE_T26_GATED_RECOMMENDED = "91364.7950";
    private static final double PRE_T26_GATED_TOTAL_CONVERSION = 836217.421;

    private FederalTaxCalculator progressiveTax() {
        var calc = mock(FederalTaxCalculator.class);
        var progressive = (org.mockito.stubbing.Answer<BigDecimal>) inv -> {
            double income = ((BigDecimal) inv.getArgument(0)).doubleValue();
            if (income <= 0) {
                return BigDecimal.ZERO;
            }
            double tax;
            if (income <= 30_000) {
                tax = income * 0.10;
            } else if (income <= 90_000) {
                tax = 30_000 * 0.10 + (income - 30_000) * 0.22;
            } else if (income <= 150_000) {
                tax = 30_000 * 0.10 + 60_000 * 0.22 + (income - 90_000) * 0.32;
            } else {
                tax = 30_000 * 0.10 + 60_000 * 0.22 + 60_000 * 0.32 + (income - 150_000) * 0.50;
            }
            return BigDecimal.valueOf(tax).setScale(4, java.math.RoundingMode.HALF_UP);
        };
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(progressive);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class), anyInt()))
                .thenAnswer(progressive);
        var bracketAnswer = (org.mockito.stubbing.Answer<BigDecimal>) inv -> {
            double r = ((BigDecimal) inv.getArgument(0)).doubleValue();
            if (r <= 0.10) {
                return new BigDecimal("30000");
            }
            if (r <= 0.22) {
                return new BigDecimal("90000");
            }
            if (r <= 0.32) {
                return new BigDecimal("150000");
            }
            return new BigDecimal("600000");
        };
        when(calc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(bracketAnswer);
        when(calc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenAnswer(bracketAnswer);
        // OrdinaryTaxTable reads raw brackets/deduction directly (audit C5) -- mirror the SAME
        // 10/22/32/50 structure the computeTax mock encodes, with 0 deduction.
        when(calc.loadOrdinaryBrackets(anyInt(), any(FilingStatus.class)))
                .thenReturn(List.of(
                        new BracketPoint(BigDecimal.ZERO, new BigDecimal("30000"), new BigDecimal("0.10")),
                        new BracketPoint(new BigDecimal("30000"), new BigDecimal("90000"), new BigDecimal("0.22")),
                        new BracketPoint(new BigDecimal("90000"), new BigDecimal("150000"), new BigDecimal("0.32")),
                        new BracketPoint(new BigDecimal("150000"), null, new BigDecimal("0.50"))));
        when(calc.loadStandardDeduction(anyInt(), any(FilingStatus.class), anyInt()))
                .thenReturn(BigDecimal.ZERO);
        return calc;
    }

    private GuardrailOptimizationInput fixture(boolean gateOnAdaptiveRules) {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
        return new GuardrailOptimizationInput(
                LocalDate.of(2030, 1, 1), 1968, 92, new BigDecimal("0.03"),
                List.of(
                        new HypotheticalAccountInput(new BigDecimal("60000"), BigDecimal.ZERO, null, "taxable"),
                        new HypotheticalAccountInput(new BigDecimal("1400000"), BigDecimal.ZERO, null,
                                "traditional")),
                List.of(),
                new BigDecimal("55000"), BigDecimal.ZERO,
                new BigDecimal("0.06"), 200, new BigDecimal("0.90"),
                phases, SEED, BigDecimal.ZERO, new BigDecimal("0.40"), 0, 0, BigDecimal.ZERO,
                "single", "roth_first", true, new BigDecimal("0.32"), new BigDecimal("0.10"), 5, null, null,
                null, null, 2030, false, null, gateOnAdaptiveRules);
    }

    private static double totalConversion(GuardrailProfileResponse r) {
        return r.conversionSchedule().years().stream()
                .mapToDouble(y -> y.conversionAmount().doubleValue())
                .sum();
    }

    @Test
    void optimize_toggleOff_pinsPreT26IdenticalBaseline() {
        var optimizer = new MonteCarloSpendingOptimizer(progressiveTax(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse r = optimizer.optimize(fixture(false));

        // Byte-identity anchor: these exact values were reproduced by the PRE-T26 code on the same
        // fixture (see class javadoc) -- the toggle-off arm scoring resolves to the identical
        // (false, 0.0) SearchContext it always used, so the whole untoggled pipeline is unchanged.
        assertThat(totalConversion(r)).isEqualTo(836217.421, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(r.conversionSchedule().years().getFirst().conversionAmount())
                .isEqualByComparingTo(new BigDecimal("64324.4170"));
        assertThat(r.yearlySpending().getFirst().recommended())
                .isEqualByComparingTo(new BigDecimal("70025.3983"));
        assertThat(r.successProbability()).isEqualByComparingTo(new BigDecimal("0.9000"));
        assertThat(r.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("0.9950"));
        assertThat(r.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_NO_ADAPTATION);
        assertThat(r.medianFinalBalance()).isEqualByComparingTo(new BigDecimal("2589529.7357"));
    }

    @Test
    void optimize_toggleOn_selectsHeavierConversionScheduleThanNoAdaptScoring() {
        var optimizer = new MonteCarloSpendingOptimizer(progressiveTax(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse off = optimizer.optimize(fixture(false));
        GuardrailProfileResponse on = optimizer.optimize(fixture(true));

        // The gated objective changes WHICH conversion schedule wins: the with-rules gate rescues
        // the early-tail damage a heavy schedule causes, so its median-path tax benefit prevails.
        // Pre-T26, the toggled run kept the toggle-off schedule (arm scoring ignored the toggle);
        // now it selects a strictly heavier one.
        assertThat(totalConversion(on)).isGreaterThan(totalConversion(off));
        assertThat(totalConversion(off)).isEqualTo(PRE_T26_GATED_TOTAL_CONVERSION,
                org.assertj.core.data.Offset.offset(1e-3));
        assertThat(totalConversion(on)).isEqualTo(1028364.1979, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(on.conversionSchedule().years().getFirst().conversionAmount())
                .isEqualByComparingTo(new BigDecimal("79104.9383"));
    }

    @Test
    void optimize_toggleOn_recommendsStrictlyMoreSpendingThanOldNoAdaptArmPipeline() {
        var optimizer = new MonteCarloSpendingOptimizer(progressiveTax(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse on = optimizer.optimize(fixture(true));

        BigDecimal recommended = on.yearlySpending().getFirst().recommended();
        // Joint optimum vs the OLD pipeline (pre-T26: no-adapt-scored arms + gated discretionary
        // search) on the SAME inputs -- the correctly-scored conversion schedule unlocks strictly
        // more certified spending. PRE_T26_GATED_RECOMMENDED was captured from the pre-T26 code
        // (see class javadoc).
        assertThat(recommended).isGreaterThan(new BigDecimal(PRE_T26_GATED_RECOMMENDED));
        // Pinned exact values (seeded, deterministic).
        assertThat(recommended).isEqualByComparingTo(new BigDecimal("91810.1460"));
        assertThat(on.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_WITH_RULES);
        assertThat(on.successProbabilityWithRules()).isEqualByComparingTo(new BigDecimal("0.9000"));
    }

    /**
     * Monotonic coherence at the pipeline level: the gated recommendation from the joint-optimum
     * arms is at least the gated recommendation the old pipeline's no-adapt-scored arms produced.
     * On the ARM scoring set this is a mathematical certainty (argmax over correctly-scored arms
     * includes the old winner); at the full-pipeline level it additionally survives the two-stage
     * path-set switch for this fixture (see the class javadoc's two-stage bias note).
     */
    @Test
    void optimize_toggleOn_jointOptimumNeverWorseThanOldArmsUnderGatedObjective() {
        var optimizer = new MonteCarloSpendingOptimizer(progressiveTax(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        GuardrailProfileResponse on = optimizer.optimize(fixture(true));

        assertThat(on.yearlySpending().getFirst().recommended().doubleValue())
                .isGreaterThanOrEqualTo(Double.parseDouble(PRE_T26_GATED_RECOMMENDED));
    }

    @Test
    void optimize_bothModesSameSeed_reproduceIdenticalResults() {
        var optimizer = new MonteCarloSpendingOptimizer(progressiveTax(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        for (boolean gate : new boolean[]{false, true}) {
            GuardrailProfileResponse a = optimizer.optimize(fixture(gate));
            GuardrailProfileResponse b = optimizer.optimize(fixture(gate));

            assertThat(a.yearlySpending().getFirst().recommended())
                    .isEqualByComparingTo(b.yearlySpending().getFirst().recommended());
            assertThat(totalConversion(a)).isEqualTo(totalConversion(b));
            assertThat(a.successProbability()).isEqualByComparingTo(b.successProbability());
            assertThat(a.medianFinalBalance()).isEqualByComparingTo(b.medianFinalBalance());
            assertThat(a.gatedOn()).isEqualTo(b.gatedOn());
        }
    }
}
