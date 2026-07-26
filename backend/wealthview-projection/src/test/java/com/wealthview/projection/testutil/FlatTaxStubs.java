package com.wealthview.projection.testutil;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.mockito.stubbing.Answer;

import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared flat-rate tax stubs, extracted from a Mockito block (both {@code computeTax} overloads
 * plus the bracket-ceiling table {@code 0.10 -> 45000 ... else 600000}) that was duplicated
 * near-verbatim across seven test sites (Task 21 audit): {@code RothConversionOptimizerTest},
 * {@code RothConversionOptimizerCharacterizationTest}, {@code ConversionSimulatorRmdConservationTest}
 * used the UNSCALED {@code income * rate} formula ({@link #flat20()} / {@link #flatRate(String)});
 * {@code RothConversionAuditC4BiasDirectionTest} and {@code MonteCarloSpendingOptimizerTest}'s
 * {@code taxAwareOptimizer()} used a {@code .setScale(4, HALF_UP)}-rounded formula AND additionally
 * stub the 4-arg (age-aware) {@code computeTax} overload ({@link #flatRateScaled(String)}).
 *
 * <p><strong>CAUTION -- these two shapes are NOT interchangeable.</strong> Several call sites feed
 * this stub's output through many compounding years of arithmetic in characterization/golden tests
 * with hand-reconciled pinned literals (e.g. {@code RothConversionOptimizerCharacterizationTest},
 * {@code RothConversionAuditC4BiasDirectionTest}); a scale-4 HALF_UP rounding on every tax call can
 * shift a compounding result by fractions of a cent relative to the unscaled formula. Each of the two
 * factory methods below reproduces one pre-existing shape EXACTLY -- do not merge them, and do not
 * swap a golden/characterization test from one shape to the other.
 *
 * <p>{@link #stubBracketCeilings(FederalTaxCalculator)} layers the shared 6-tier bracket-ceiling
 * table (used by {@link #flat20()}'s original call sites) onto an already-created mock, stubbing
 * both the 3-arg and 4-arg (inflation-indexed) {@code computeMaxIncomeForBracket} overloads
 * identically -- exactly as the original inline blocks did. Callers whose bracket-ceiling shape
 * differs (abbreviated tiers, a flat unconditional ceiling, etc.) intentionally do NOT use this
 * method and keep their own stubbing inline.
 *
 * <p>{@link #flatTaxStrategy(String)} is the {@link TaxCalculationStrategy}-typed sibling migrated
 * from {@code MultiPoolDeepTest.flatTaxCalc} (the audit's "7th variant") -- a different interface
 * entirely (used to build a {@code PoolStrategy.PoolConfig} directly rather than mocking
 * {@link FederalTaxCalculator}), included here because it is the same "flat proportional tax" idea
 * this class collects.
 */
public final class FlatTaxStubs {

    private FlatTaxStubs() {
    }

    /** Convenience for {@code flatRate("0.20")} -- the rate every migrated call site actually used. */
    public static FederalTaxCalculator flat20() {
        return flatRate("0.20");
    }

    /**
     * A {@link FederalTaxCalculator} mock stubbing ONLY the 3-arg {@code computeTax} overload with
     * the unscaled formula {@code income <= 0 ? 0 : income * rate}. Matches the shape originally
     * inlined in {@code RothConversionOptimizerTest}, {@code RothConversionOptimizerCharacterizationTest},
     * and {@code ConversionSimulatorRmdConservationTest} byte-for-byte -- no {@code .setScale(...)},
     * no 4-arg overload stubbed. Callers that also need bracket ceilings must call
     * {@link #stubBracketCeilings(FederalTaxCalculator)} separately.
     */
    public static FederalTaxCalculator flatRate(String rate) {
        var calc = mock(FederalTaxCalculator.class);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(invocation -> {
                    BigDecimal income = invocation.getArgument(0);
                    if (income.compareTo(BigDecimal.ZERO) <= 0) {
                        return BigDecimal.ZERO;
                    }
                    return income.multiply(new BigDecimal(rate));
                });
        return calc;
    }

    /**
     * A {@link FederalTaxCalculator} mock stubbing BOTH the 3-arg and 4-arg (age-aware) {@code
     * computeTax} overloads identically, each rounding to 4 decimal places with {@code HALF_UP}.
     * Matches the shape originally inlined in {@code RothConversionAuditC4BiasDirectionTest} and
     * {@code MonteCarloSpendingOptimizerTest.taxAwareOptimizer()} -- both need the age-aware
     * overload because {@code MarginalRateCalculator} always calls it once {@code
     * GuardrailOptimizationInput#birthYear()} is non-null. Bracket-ceiling / ordinary-bracket
     * stubbing is intentionally NOT bundled here: the two known callers use genuinely different
     * ceiling shapes and keep that stubbing inline.
     */
    public static FederalTaxCalculator flatRateScaled(String rate) {
        var calc = mock(FederalTaxCalculator.class);
        Answer<BigDecimal> taxAnswer = invocation -> {
            BigDecimal income = invocation.getArgument(0);
            return income.compareTo(BigDecimal.ZERO) <= 0
                    ? BigDecimal.ZERO
                    : income.multiply(new BigDecimal(rate)).setScale(4, RoundingMode.HALF_UP);
        };
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class))).thenAnswer(taxAnswer);
        when(calc.computeTax(any(BigDecimal.class), anyInt(), any(FilingStatus.class), anyInt()))
                .thenAnswer(taxAnswer);
        return calc;
    }

    /**
     * Layers the shared 6-tier bracket-ceiling table (rate {@code <= 0.10 -> 45000}, {@code <= 0.12
     * -> 55000}, {@code <= 0.22 -> 100000}, {@code <= 0.24 -> 190000}, {@code <= 0.32 -> 245000},
     * else {@code 600000}) onto an already-created mock, stubbing both the 3-arg and 4-arg
     * (inflation-indexed) {@code computeMaxIncomeForBracket} overloads with the identical answer --
     * exactly as originally inlined in {@code RothConversionOptimizerTest} and {@code
     * RothConversionOptimizerCharacterizationTest}.
     */
    public static void stubBracketCeilings(FederalTaxCalculator calc) {
        Answer<BigDecimal> bracketAnswer = invocation -> {
            BigDecimal rate = invocation.getArgument(0);
            double r = rate.doubleValue();
            if (r <= 0.10) {
                return new BigDecimal("45000");
            }
            if (r <= 0.12) {
                return new BigDecimal("55000");
            }
            if (r <= 0.22) {
                return new BigDecimal("100000");
            }
            if (r <= 0.24) {
                return new BigDecimal("190000");
            }
            if (r <= 0.32) {
                return new BigDecimal("245000");
            }
            return new BigDecimal("600000");
        };
        when(calc.computeMaxIncomeForBracket(any(BigDecimal.class), anyInt(), any(FilingStatus.class)))
                .thenAnswer(bracketAnswer);
        when(calc.computeMaxIncomeForBracket(
                any(BigDecimal.class), anyInt(), any(FilingStatus.class), nullable(BigDecimal.class)))
                .thenAnswer(bracketAnswer);
    }

    /**
     * A flat-proportional {@link TaxCalculationStrategy} -- migrated verbatim from {@code
     * MultiPoolDeepTest.flatTaxCalc} (the audit's "7th variant"): {@code computeTotalTax}/{@code
     * computeDetailedTax} both apply {@code gross * rate} with no rounding, and {@code
     * computeMaxIncomeForTargetRate} always returns a fixed $100,000 ceiling regardless of the
     * requested target rate, matching the original exactly.
     */
    public static TaxCalculationStrategy flatTaxStrategy(String rate) {
        BigDecimal r = new BigDecimal(rate);
        return new TaxCalculationStrategy() {
            @Override
            public BigDecimal computeTotalTax(BigDecimal gross, int yr, FilingStatus fs) {
                return gross.multiply(r);
            }

            @Override
            public BigDecimal computeMaxIncomeForTargetRate(BigDecimal targetRate, int yr, FilingStatus fs) {
                return new BigDecimal("100000");
            }

            @Override
            public CombinedTaxResult computeDetailedTax(BigDecimal gross, int yr, FilingStatus fs) {
                BigDecimal total = gross.multiply(r);
                return new CombinedTaxResult(total, BigDecimal.ZERO, total, BigDecimal.ZERO, BigDecimal.ZERO, false);
            }
        };
    }
}
