package com.wealthview.projection;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
 * tax falls from $405,160 to $337,312 — while the slower, constant-real drawdown leaves a small
 * traditional balance at the end age. These assertions are the contract the decomposition must preserve.
 */
class RothConversionOptimizerCharacterizationTest {

    private FederalTaxCalculator taxCalculator;

    @BeforeEach
    void setUp() {
        taxCalculator = mock(FederalTaxCalculator.class);
        when(taxCalculator.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(inv -> {
                    BigDecimal income = inv.getArgument(0);
                    if (income.compareTo(BigDecimal.ZERO) <= 0) {
                        return BigDecimal.ZERO;
                    }
                    return income.multiply(new BigDecimal("0.20"));
                });
        org.mockito.stubbing.Answer<BigDecimal> bracketAnswer = inv -> {
            double r = ((BigDecimal) inv.getArgument(0)).doubleValue();
            if (r <= 0.10) return new BigDecimal("45000");
            if (r <= 0.12) return new BigDecimal("55000");
            if (r <= 0.22) return new BigDecimal("100000");
            if (r <= 0.24) return new BigDecimal("190000");
            if (r <= 0.32) return new BigDecimal("245000");
            return new BigDecimal("600000");
        };
        when(taxCalculator.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(bracketAnswer);
        when(taxCalculator.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenAnswer(bracketAnswer);
    }

    private static final double TOL = 1e-6;

    /** Single filer, $900K traditional IRA, retirement at 62, end age 90. */
    private RothConversionOptimizer goldenOptimizer() {
        return new RothConversionOptimizer.Builder()
                .portfolio(900_000, 50_000, 150_000)
                .income(new double[28], new double[28])
                .demographics(1963, 62, 90)
                .taxConfig(0.22, 0.12, 0.10, FilingStatus.SINGLE, taxCalculator)
                .assumptions(0.06, 40_000, 5, "taxable,traditional,roth")
                .build();
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsScheduleSummary() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()).hasSize(28);
        assertThat(schedule.conversionFraction()).isEqualTo(1.0, offset(TOL));
        assertThat(schedule.lifetimeTaxWith()).isEqualTo(337311.70943966054, offset(1e-3));
        assertThat(schedule.lifetimeTaxWithout()).isEqualTo(405159.95124977175, offset(1e-3));
        assertThat(schedule.exhaustionAge()).isEqualTo(90);
        // Real terms: constant-real (lower) spending draws down traditional more slowly, so it is not
        // fully exhausted to the target by the end age (see pinsFinalYear: $46,131 remains).
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

        assertThat(schedule.conversionByYear()[0]).isEqualTo(100000.0, offset(1e-3));
        assertThat(schedule.conversionTaxByYear()[0]).isEqualTo(20000.0, offset(1e-3));
        assertThat(schedule.traditionalBalance()[0]).isEqualTo(854000.0, offset(1e-3));
        assertThat(schedule.rothBalance()[0]).isEqualTo(153000.0, offset(1e-3));
        assertThat(schedule.taxableBalance()[0]).isEqualTo(99000.0, offset(1e-3));
        assertThat(schedule.projectedRmd()[0]).isEqualTo(0.0, offset(TOL));
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsSecondConversionYear() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()[1]).isEqualTo(100000.0, offset(1e-3));
        assertThat(schedule.traditionalBalance()[1]).isEqualTo(805240.0, offset(1e-3));
        assertThat(schedule.rothBalance()[1]).isEqualTo(262180.0, offset(1e-3));
        assertThat(schedule.taxableBalance()[1]).isEqualTo(44940.0, offset(1e-3));
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsRmdYear() {
        var schedule = goldenOptimizer().optimize();

        // Index 13 = age 75, first year an RMD applies for a 1963 birth year.
        assertThat(schedule.conversionByYear()[13]).isEqualTo(0.0, offset(TOL));
        assertThat(schedule.traditionalBalance()[13]).isEqualTo(568949.8286769011, offset(1e-3));
        assertThat(schedule.rothBalance()[13]).isEqualTo(896472.2964736733, offset(1e-3));
        assertThat(schedule.projectedRmd()[13]).isEqualTo(24284.169272487674, offset(1e-3));
    }

    @Test
    void optimize_largeTraditionalIraScenario_pinsFinalYear() {
        var schedule = goldenOptimizer().optimize();

        assertThat(schedule.conversionByYear()[27]).isEqualTo(0.0, offset(TOL));
        assertThat(schedule.traditionalBalance()[27]).isEqualTo(46131.06304972322, offset(1e-3));
        assertThat(schedule.rothBalance()[27]).isEqualTo(2026837.7613215828, offset(1e-3));
        assertThat(schedule.taxableBalance()[27]).isEqualTo(0.0, offset(TOL));
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
