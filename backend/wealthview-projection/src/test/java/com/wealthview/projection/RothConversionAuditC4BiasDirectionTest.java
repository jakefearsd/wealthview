package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit C4 direction check: the frame fix ({@link OptimizationContextBuilder#resolveReturnMean})
 * must bias the Roth-conversion optimizer LESS aggressively toward conversion, not more.
 *
 * <p>The fixture is DS-mode (dynamic sequencing) so {@link JointConversionSearch} takes the
 * deterministic Phase-1 path ({@code RothConversionOptimizer.optimize()} -- pure lifetime-tax
 * minimization, no Monte Carlo search noise), making the before/after comparison a clean,
 * reproducible A/B rather than a search-driven approximation.
 *
 * <p><strong>Attribution — this fixture's movement is 100% the FRAME fix; the RMD-proceeds credit
 * contributes $0 here, structurally.</strong> In DS mode the credit CANNOT affect any asserted
 * value: (1) credits only occur at/after RMD age, when conversions have already ceased ({@code age
 * >= rmdStartAge} blocks them), so conversion amounts/taxes are untouched; (2) the DS withdrawal
 * strategy sizes its taxed traditional draw from bracket space alone -- BEFORE consulting the
 * taxable balance -- and taxable/Roth draws are untaxed, so a bigger credited taxable pool changes
 * neither the traditional trajectory (and hence rmdTax) nor withdrawalTax; lifetime tax in both
 * arms is therefore independent of the credit. Verified empirically by the T11 reviewer at commit
 * dd43b65 (frame fix alone reproduces the exact post-fix figures below). The credit's own effect
 * is pinned where it IS live -- ordered-withdrawal fixtures -- by
 * {@code RothConversionOptimizerCharacterizationTest} (whose explicit real {@code returnMean}
 * conversely bypasses the frame fix, giving each fix exactly one isolating pin).
 *
 * <p>The "before" figures below were captured by running this exact fixture against the pre-fix
 * code (default {@code returnMean = 0.10} hardcoded in {@code JointConversionSearch}, RMD proceeds
 * silently discarded in {@code applyRmds}) via a throwaway scratch test, then deleted -- standard
 * golden-regen discipline for this repo (see {@code docs/audits/2026-07-11-projection-accuracy-audit-v2.md}
 * item C4 and the T6 addendum in {@code .superpowers/sdd/progress.md}).
 */
class RothConversionAuditC4BiasDirectionTest {

    // Captured against pre-fix JointConversionSearch (returnMean default = 0.10 nominal-as-real,
    // ConversionSimulator.applyRmds crediting nothing) for the exact fixture built below.
    private static final double PRE_FIX_TOTAL_CONVERSION = 486514.0154;
    private static final double PRE_FIX_LIFETIME_TAX_WITHOUT = 435426.9618;
    private static final double PRE_FIX_LIFETIME_TAX_WITH = 401413.8165;
    private static final double PRE_FIX_TAX_SAVINGS = 34013.1453;

    private FederalTaxCalculator flatRateTaxCalculator() {
        var calc = mock(FederalTaxCalculator.class);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    return income.compareTo(BigDecimal.ZERO) <= 0
                            ? BigDecimal.ZERO
                            : income.multiply(new BigDecimal("0.20")).setScale(4, java.math.RoundingMode.HALF_UP);
                });
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class), anyInt()))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    return income.compareTo(BigDecimal.ZERO) <= 0
                            ? BigDecimal.ZERO
                            : income.multiply(new BigDecimal("0.20")).setScale(4, java.math.RoundingMode.HALF_UP);
                });
        org.mockito.stubbing.Answer<BigDecimal> bracketAnswer = inv -> {
            double r = ((BigDecimal) inv.getArgument(0)).doubleValue();
            if (r <= 0.10) return new BigDecimal("45000");
            if (r <= 0.12) return new BigDecimal("55000");
            if (r <= 0.22) return new BigDecimal("100000");
            return new BigDecimal("600000");
        };
        when(calc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(bracketAnswer);
        when(calc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenAnswer(bracketAnswer);
        // Audit C5: OrdinaryTaxTable reads raw brackets/deduction directly, not computeTax --
        // mirror the SAME flat-20% formula the computeTax mock above encodes (0 deduction), so the
        // new exact table agrees with the old flat-mock formula exactly.
        when(calc.loadOrdinaryBrackets(anyInt(), any(FilingStatus.class)))
                .thenReturn(List.of(new com.wealthview.core.projection.tax.BracketPoint(
                        BigDecimal.ZERO, null, new BigDecimal("0.20"))));
        when(calc.loadStandardDeduction(anyInt(), any(FilingStatus.class), anyInt()))
                .thenReturn(BigDecimal.ZERO);
        return calc;
    }

    private GuardrailOptimizationInput fixedScenario() {
        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));
        return GuardrailOptimizationInputBuilder.builder()
                .withBirthYear(1963)
                .withAccounts(List.of(
                        new HypotheticalAccountInput(new BigDecimal("150000"),
                                BigDecimal.ZERO, null, "taxable"),
                        new HypotheticalAccountInput(new BigDecimal("900000"),
                                BigDecimal.ZERO, null, "traditional"),
                        new HypotheticalAccountInput(new BigDecimal("50000"),
                                BigDecimal.ZERO, null, "roth")))
                .withEssentialFloor(new BigDecimal("40000"))
                .withReturnMean(null) // returnMean: unset -> default resolution (the seam under test)
                .withTrialCount(500)
                .withPhases(phases)
                .withFilingStatus("single")
                .withWithdrawalOrder("dynamic_sequencing")
                .withOptimizeConversions(true)
                .withConversionBracketRate(new BigDecimal("0.22"))
                .withRmdTargetBracketRate(new BigDecimal("0.12"))
                .withDynamicSequencingBracketRate(new BigDecimal("0.22"))
                .build();
    }

    @Test
    void optimize_fixedScenario_conversionScheduleAndLifetimeTaxWithoutBothShrinkVsPreFix() {
        var optimizer = new MonteCarloSpendingOptimizer(flatRateTaxCalculator(), ProjectionTestFixtures.TEST_CMA_MATRIX);

        var result = optimizer.optimize(fixedScenario());
        var schedule = result.conversionSchedule();

        double totalConversion = schedule.years().stream()
                .mapToDouble(y -> y.conversionAmount().doubleValue())
                .sum();

        // Direction: the FRAME fix pushes toward LESS aggressive conversion (lower real growth ->
        // less projected future traditional balance -> less RMD-bracket pressure -> smaller
        // target-balance excess to convert, and lower tax in BOTH arms from slower growth). The
        // RMD credit contributes $0 to every asserted figure in this DS fixture -- see the class
        // Javadoc's attribution note.
        assertThat(totalConversion)
                .as("post-fix total conversion must be smaller than the pre-fix baseline")
                .isLessThan(PRE_FIX_TOTAL_CONVERSION);
        assertThat(schedule.lifetimeTaxWithout().doubleValue())
                .as("post-fix lifetimeTaxWithout must drop -- slower (real, fee-adjusted) growth means"
                        + " smaller RMDs and withdrawals to tax in the baseline arm")
                .isLessThan(PRE_FIX_LIFETIME_TAX_WITHOUT);
        assertThat(schedule.lifetimeTaxWithConversions().doubleValue())
                .as("post-fix lifetimeTaxWith must also drop (same slower-growth mechanism in the with-conversion arm)")
                .isLessThan(PRE_FIX_LIFETIME_TAX_WITH);
        assertThat(schedule.taxSavings().doubleValue())
                .as("claimed tax_savings must shrink, not grow, once the pro-conversion frame bias is corrected")
                .isLessThan(PRE_FIX_TAX_SAVINGS);

        // Pin the exact post-fix values so this test also serves as a regression pin, not just a
        // one-time direction check.
        assertThat(totalConversion).isEqualTo(166603.3475, offset(1e-2));
        assertThat(schedule.lifetimeTaxWithout().doubleValue()).isEqualTo(340629.8078, offset(1e-2));
        assertThat(schedule.lifetimeTaxWithConversions().doubleValue()).isEqualTo(326444.3397, offset(1e-2));
        assertThat(schedule.taxSavings().doubleValue()).isEqualTo(14185.4681, offset(1e-2));
    }
}
