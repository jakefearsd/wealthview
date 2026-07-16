package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.ConversionYearDetail;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.RothConversionScheduleResponse;
import com.wealthview.core.projection.dto.StochasticMortalityResponse;

import static com.wealthview.core.common.Money.ROUNDING;
import static com.wealthview.core.common.Money.SCALE;

/**
 * Assembles the {@link GuardrailProfileResponse} from a completed optimization: runs the
 * terminal withdrawal simulation to derive final-balance statistics, computes the spending
 * corridors, and maps everything to the response DTOs. Extracted from
 * {@link MonteCarloSpendingOptimizer} to keep that class focused on the optimization itself.
 */
final class GuardrailResponseBuilder {

    private static final Logger log = LoggerFactory.getLogger(GuardrailResponseBuilder.class);

    /** Tolerance (dollars) for detecting a floor clamp (audit C6) past floating-point noise. */
    private static final double FLOOR_CLAMP_EPSILON = 1e-6;

    private final TrialSimulator trialSimulator;

    GuardrailResponseBuilder(TrialSimulator trialSimulator) {
        this.trialSimulator = trialSimulator;
    }

    GuardrailProfileResponse build(OptimizationSetup ctx,
                                   GuardrailOptimizationInput input,
                                   double[] discretionaryByYear,
                                   double[] conversionByYear,
                                   double[] conversionTaxByYear,
                                   RothConversionOptimizer.RothConversionSchedule convSchedule,
                                   @Nullable StochasticMortalitySummary stochasticMortality) {
        // HP2: {@code discretionaryByYear} arrives ALREADY survivor-scaled from the transition year
        // (MonteCarloSpendingOptimizer.allocateAndSmooth's single scaling seam, mirroring the
        // essential-floor pre-scaling in OptimizationContextBuilder). This one array is what every
        // trial pass below SIMULATES and what the corridor + yearly_spending REPORT, so the certified
        // success rate measures exactly the schedule shown to the user (T8's earlier reporting-only
        // clone is gone — no second scaling here, single source of truth). No-op for single-person.
        double[][] corridors = SpendingCorridorCalculator.computeCorridors(
                ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(), ctx.taxIncome().adjustedFloors(),
                discretionaryByYear, ctx.sim().years(), ctx.sim().trialCount());
        SpendingCorridorCalculator.smoothCorridors(corridors[0], corridors[1], ctx.sim().years());

        // Clamp corridors to bracket recommended spending (smoothing can overshoot at phase boundaries)
        for (int y = 0; y < ctx.sim().years(); y++) {
            double recommended = ctx.taxIncome().adjustedFloors()[y] + discretionaryByYear[y];
            corridors[0][y] = Math.min(corridors[0][y], recommended);
            corridors[1][y] = Math.max(corridors[1][y], recommended);
        }

        // Simulate with withdrawals to get final balances and per-year median balances
        var poolSetup = resolvePoolSetup(ctx, conversionByYear);

        double[][] yearBalances = new double[ctx.sim().years()][ctx.sim().trialCount()];
        double[] finalBalances = new double[ctx.sim().trialCount()];
        int tradExhaustedCount = 0;
        int successCount = 0;
        for (int t = 0; t < ctx.sim().trialCount(); t++) {
            // The exact per-year tax tables (audit C5: ordinaryTaxTableByYear plus the base
            // ordinary-income array draws stack on) mirror the search: real withdrawal tax is
            // deducted whenever pools (traditional/roth) are in play, so reported balances match
            // what the optimizer actually modeled. Each trial reuses the per-pool real return
            // sequences generated for the run.
            var simConfig = buildSimConfig(ctx, t, poolSetup, conversionByYear, conversionTaxByYear, true, null);

            var result = trialSimulator.simulateTrial(
                    ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                    ctx.taxIncome().adjustedFloors(), discretionaryByYear,
                    ctx.sim().years(), simConfig);
            for (int y = 0; y < ctx.sim().years(); y++) {
                yearBalances[y][t] = result.yearBalances()[y];
            }
            finalBalances[t] = result.finalBalance();
            if (result.traditionalExhausted()) {
                tradExhaustedCount++;
            }
            if (result.success()) {
                successCount++;
            }
        }
        double mcExhaustionPct = conversionByYear != null
                ? (double) tradExhaustedCount / ctx.sim().trialCount() : 0;

        double[] medianBalanceByYear = new double[ctx.sim().years()];
        double[] p10BalanceByYear = new double[ctx.sim().years()];
        double[] p25BalanceByYear = new double[ctx.sim().years()];
        for (int y = 0; y < ctx.sim().years(); y++) {
            Arrays.sort(yearBalances[y]);
            p10BalanceByYear[y] = percentile(yearBalances[y], 0.10);
            p25BalanceByYear[y] = percentile(yearBalances[y], 0.25);
            medianBalanceByYear[y] = percentile(yearBalances[y], 0.50);
        }

        Arrays.sort(finalBalances);
        double medianFinal = percentile(finalBalances, 0.50);
        double p10Final = percentile(finalBalances, 0.10);
        // successProbability uses the same essential-floor-funded definition as the optimizer's
        // sustainability search (TrialResult.success()), not final-balance depletion, so the
        // reported failure rate is consistent with what the optimizer actually optimized for.
        //
        // HP3 Part C: for a household run, "reported success != the gate's success" has TWO
        // stacked, independently documented causes, not one -- (1) T24: this headline
        // successProbability is ALWAYS the no-adaptation pass on the final schedule, while the
        // search's own gate may have certified against the WITH-RULES rate instead when
        // gateOnAdaptiveRules is set (see resolveGatedOn below, and successProbabilityWithRules for
        // the with-rules headline). (2) HP2: the search's level-finding
        // (SustainabilitySearch/allocateSpending, reduceUntilSustainable) validates the candidate
        // discretionary schedule on the UNSCALED level BEFORE the survivor-factor scaling is
        // applied, while this headline (and the reported yearly_spending) are always computed on
        // the FINAL, scaled schedule -- see MonteCarloSpendingOptimizer#allocateAndSmooth's single
        // scaling seam. Neither gap is a bug: (1) is disclosed via
        // GuardrailProfileResponse.Disclosure#gatedOn; (2) is the deliberately conservative HP2
        // design -- the >= confidence guarantee holds by construction because the scaled schedule
        // draws LESS than what the search certified.
        double successProbability = (double) successCount / ctx.sim().trialCount();
        double failureRate = 1.0 - successProbability;

        // Audit C6: verifyEssentialFloor silently clamps an unaffordable floor down to portfolio
        // capacity (SustainabilitySearch.verifyEssentialFloor) -- successProbability above then
        // measures that REDUCED floor, not the floor the user asked for. Disclose the clamp and,
        // when it happened, the success rate against the user's TRUE (unclamped) floor via one
        // extra simulation pass over the same trials/discretionary plan/conversion schedule already
        // computed above. Behavior/gating semantics are untouched -- this is read-only disclosure.
        boolean floorReduced = isFloorReduced(ctx.taxIncome().adjustedFloors(), ctx.taxIncome().essentialFloor());
        BigDecimal originalFloorSuccessProbability = floorReduced
                ? toBD(computeOriginalFloorSuccessRate(ctx, discretionaryByYear, poolSetup,
                        conversionByYear, conversionTaxByYear))
                : null;

        // Audit C9: reporting-only with-rules success rate -- a SECOND pass over the same trials
        // with the simulated guardrail adaptation active. Returns null when no positive
        // max_annual_adjustment_rate is configured. The optimizer's gate/objective are unchanged.
        BigDecimal successProbabilityWithRules = computeSuccessProbabilityWithRules(ctx, discretionaryByYear,
                poolSetup, conversionByYear, conversionTaxByYear, medianBalanceByYear,
                input.maxAnnualAdjustmentRate());

        var yearlySpending = buildYearlySpending(ctx, input, discretionaryByYear, corridors,
                medianBalanceByYear, p10BalanceByYear, p25BalanceByYear);

        log.info("MC optimization complete: {} trials, {} years, median final balance {}",
                ctx.sim().trialCount(), ctx.sim().years(), toBD(medianFinal));

        RothConversionScheduleResponse convScheduleResponse =
                buildConvScheduleResponse(ctx, input, convSchedule, mcExhaustionPct);

        return new GuardrailProfileResponse(
                null, null, "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                // Audit C4: echo the RESOLVED effective REAL, fee-adjusted growth rate the
                // conversion simulator actually used (OptimizationContextBuilder.resolveReturnMean)
                // — not the raw request value, which is null for all frontend traffic and NOMINAL
                // when explicitly supplied. Display-only wire field; also what gets persisted.
                toBD(ctx.sim().returnMean()),
                ctx.sim().trialCount(), input.confidenceLevel(),
                input.phases(), yearlySpending,
                toBD(medianFinal), toBD(failureRate), toBD(successProbability),
                toBD(p10Final),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                input.portfolioFloor(), input.maxAnnualAdjustmentRate(),
                input.phaseBlendYears(), null,
                input.cashReserveYears(), input.cashReturnRate(),
                convScheduleResponse,
                new GuardrailProfileResponse.Disclosure(null, floorReduced, originalFloorSuccessProbability,
                        successProbabilityWithRules,
                        // T24: which metric actually certified this run's search gate.
                        GuardrailProfileResponse.resolveGatedOn(
                                input.gateOnAdaptiveRules(), input.maxAnnualAdjustmentRate())),
                toStochasticMortalityResponse(stochasticMortality));
    }

    /**
     * Sub-project B (stochastic mortality), task 8: maps the projection-internal {@link
     * StochasticMortalitySummary} (task 7's aggregation) onto the wire-facing {@link
     * StochasticMortalityResponse} -- {@code null} in, {@code null} out, mirroring how {@link
     * #buildConvScheduleResponse} handles its own optional nested block. No {@code from(...)}
     * factory lives on {@link StochasticMortalityResponse} itself: it is a core DTO and {@code
     * StochasticMortalitySummary} is projection-internal, so the mapping happens here, in
     * projection code that can see both types (module dependency direction is projection -&gt;
     * core, never the reverse).
     */
    @Nullable
    private static StochasticMortalityResponse toStochasticMortalityResponse(
            @Nullable StochasticMortalitySummary summary) {
        if (summary == null) {
            return null;
        }
        var longevity = summary.longevityConditional();
        var firstDeath = summary.firstDeathAge();
        var secondDeath = summary.secondDeathAge();
        return new StochasticMortalityResponse(
                toBD(summary.lifetimeSuccessProbability()),
                new StochasticMortalityResponse.LongevityConditionalResponse(
                        longevity.age(), toBD(longevity.probability()), toBD(longevity.trialFraction())),
                new StochasticMortalityResponse.AgeDistributionResponse(
                        firstDeath.p10(), firstDeath.median(), firstDeath.p90()),
                new StochasticMortalityResponse.AgeDistributionResponse(
                        secondDeath.p10(), secondDeath.median(), secondDeath.p90()));
    }

    /** Pool balances/order the terminal simulation grows and withdraws from (audit C6 extraction). */
    private record PoolSimSetup(
            double initTaxable, double initTraditional, double initRoth, String order, boolean simPools) {}

    // UseVarargs: conversionByYear is a per-year indexed array, not a variable argument list --
    // varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private PoolSimSetup resolvePoolSetup(OptimizationSetup ctx, double[] conversionByYear) {
        boolean simPools = conversionByYear != null
                || ctx.portfolio().initTraditional() > 0 || ctx.portfolio().initRoth() > 0;
        double initTaxable = simPools
                ? ctx.portfolio().initTaxable() : ctx.portfolio().initialPortfolio();
        double initTraditional = simPools ? ctx.portfolio().initTraditional() : 0;
        double initRoth = simPools ? ctx.portfolio().initRoth() : 0;
        String order = simPools && ctx.portfolio().withdrawalOrder() != null
                ? ctx.portfolio().withdrawalOrder() : "taxable_first";
        return new PoolSimSetup(initTaxable, initTraditional, initRoth, order, simPools);
    }

    /** Builds trial {@code t}'s {@link TrialSimulator.SimulationConfig} (audit C6 extraction, shared
     * by the headline terminal simulation and the floor-clamp disclosure's extra pass).
     * {@code adaptation} is {@code null} for the no-rules passes (byte-identical to pre-C9) and
     * non-null for the audit-C9 with-rules pass. */
    private TrialSimulator.SimulationConfig buildSimConfig(OptimizationSetup ctx, int t, PoolSimSetup poolSetup,
                                                            double[] conversionByYear, double[] conversionTaxByYear,
                                                            boolean trackYearBalances,
                                                            TrialSimulator.GuardrailAdaptation adaptation) {
        return new TrialSimulator.SimulationConfig(
                poolSetup.initTaxable(), poolSetup.initTraditional(), poolSetup.initRoth(), poolSetup.order(),
                poolSetup.simPools() ? ctx.taxIncome().ordinaryTaxTableByYear() : null,
                poolSetup.simPools() ? ctx.taxIncome().rentalAwareTaxableIncome() : null,
                conversionByYear, conversionTaxByYear, ctx.sim().retirementAge(),
                ctx.taxIncome().dsBracketCeilingByYear(),
                ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(), trackYearBalances,
                ctx.sim().taxableReturns()[t], ctx.sim().traditionalReturns()[t], ctx.sim().rothReturns()[t],
                ctx.sim().rmdStartAge(),
                ctx.portfolio().initTaxableBasis(), ctx.taxIncome().ltcgTaxTableByYear(),
                ctx.sim().dividendYield(), adaptation,
                // T18a-3: threaded into the LTCG/NIIT bundle's Net Investment Income base.
                poolSetup.simPools() ? ctx.taxIncome().rentalIncomeByYear() : null,
                ctx.sim().interestYield(), ctx.sim().taxableEquityShare(),
                // Household task 6: the terminal/response passes run the same household economics as
                // the search (per-owner RMD streams, first-death transition, second-death truncation).
                ctx.sim().household());
    }

    /** Household task 6 (dedup): scales {@code values} in place by the survivor spending factor from
     * the transition year via the single shared {@link HouseholdMcResolver#scaleFromTransition},
     * mirroring the pre-scaling {@link OptimizationContextBuilder} applies to {@code adjustedFloors}.
     * No-op for a single-person run (no household). Used by the floor-clamp disclosure's re-scaled
     * original-floor array; the discretionary/recommended schedule and its corridor are pre-scaled
     * ONCE upstream (HP2, {@link MonteCarloSpendingOptimizer#allocateAndSmooth}) so this class no
     * longer clones-and-scales them here. */
    private static void scaleSurvivorFactor(double[] values, OptimizationSetup ctx, int years) {
        HouseholdMcResolver.scaleFromTransition(
                values, ctx.sim().household(), ctx.sim().survivorSpendingFactor(), years);
    }

    /** {@code true} when {@code adjustedFloors} was clamped below the user's {@code essentialFloor}
     * in at least one year (audit C6). {@code adjustedFloors} is constant-real, so any year strictly
     * below {@code essentialFloor} (beyond floating-point noise) can only be
     * {@code SustainabilitySearch.verifyEssentialFloor}'s capacity clamp. */
    private static boolean isFloorReduced(double[] adjustedFloors, double essentialFloor) {
        for (double floor : adjustedFloors) {
            if (floor < essentialFloor - FLOOR_CLAMP_EPSILON) {
                return true;
            }
        }
        return false;
    }

    /** One extra simulation pass (audit C6) measuring the success rate of the SAME discretionary
     * plan and conversion schedule already optimized, against the user's UNCLAMPED essential floor
     * (constant {@code essentialFloor} every year) instead of the clamped {@code adjustedFloors}.
     * Reuses the same per-trial return sequences as the headline pass so the two success rates are
     * directly comparable. Read-only: does not feed back into optimization or gating. */
    // UseVarargs: the trailing double[] params are per-year indexed arrays, not a variable
    // argument list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private double computeOriginalFloorSuccessRate(OptimizationSetup ctx, double[] discretionaryByYear,
                                                    PoolSimSetup poolSetup, double[] conversionByYear,
                                                    double[] conversionTaxByYear) {
        int years = ctx.sim().years();
        int trialCount = ctx.sim().trialCount();
        double[] originalFloors = new double[years];
        Arrays.fill(originalFloors, ctx.taxIncome().essentialFloor());
        // Household task 6: the survivor floor is scaled by the survivor spending factor from the
        // first-death transition year (mirroring the main adjustedFloors, which OptimizationContextBuilder
        // already pre-scales). No-op for single-person (factor 1.0 / no in-window transition).
        scaleSurvivorFactor(originalFloors, ctx, years);

        int successCount = 0;
        for (int t = 0; t < trialCount; t++) {
            var simConfig = buildSimConfig(ctx, t, poolSetup, conversionByYear, conversionTaxByYear, false, null);
            var result = trialSimulator.simulateTrial(
                    ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                    originalFloors, discretionaryByYear, years, simConfig);
            if (result.success()) {
                successCount++;
            }
        }
        return (double) successCount / trialCount;
    }

    /**
     * Audit C9: the essential-floor success rate with the SIMULATED guardrail adaptation rule
     * active. Re-runs the SAME trials (same per-pool return sequences, same
     * floors/discretionary/conversion inputs) as the headline no-rules terminal pass, but with the
     * spending adaptation switched on -- so both success rates are directly comparable. Reporting
     * only: nothing here feeds back into the optimizer's gate/objective.
     *
     * <p>Corridor coupling, precisely: each trial-year forms the portfolio-ratio PROXY
     * {@code plannedSpending * (trialStart / expectedStartBalance[y])} and GATES it by the
     * DISPLAYED corridor thresholds -- the proxy is not derived the way the corridor itself was
     * (the corridor comes from cross-trial percentile dispersion in
     * {@link SpendingCorridorCalculator}). When the proxy breaches the lower band the rule cuts
     * discretionary toward the PROXY (bounded by {@code maxAdjRate}) -- so simulated spending CAN
     * fall below {@code corridor_low}, floor-bounded; otherwise it recovers toward plan (never
     * above plan). The reported rate therefore measures "success when following this specific
     * ratio-cut rule", not "spending always kept inside the shown corridor". The
     * expected-start-balance reference is the no-adaptation median trajectory: year 0 is the initial
     * portfolio, year {@code y>0} is the prior year's median end-of-year balance
     * ({@code medianBalanceByYear[y-1]}) from the headline pass -- a start-of-year proxy.
     *
     * <p>Returns {@code null} when there is no positive {@code maxAnnualAdjustmentRate} (no
     * adaptation configured -- the rule cannot move spending, so the with-rules rate would equal the
     * no-rules rate) so the extra pass is skipped entirely.
     */
    private BigDecimal computeSuccessProbabilityWithRules(OptimizationSetup ctx, double[] discretionaryByYear,
                                                          PoolSimSetup poolSetup, double[] conversionByYear,
                                                          double[] conversionTaxByYear,
                                                          double[] medianBalanceByYear,
                                                          BigDecimal maxAnnualAdjustmentRate) {
        double maxAdjRate = maxAnnualAdjustmentRate != null ? maxAnnualAdjustmentRate.doubleValue() : 0.0;
        if (maxAdjRate <= 0) {
            return null;
        }
        int years = ctx.sim().years();
        int trialCount = ctx.sim().trialCount();

        double initTotalBalance = poolSetup.initTaxable() + poolSetup.initTraditional() + poolSetup.initRoth();
        var adaptation = GuardrailRuleInputBuilder.build(ctx.sim().portfolioPaths(), ctx.taxIncome().incomeByYear(),
                ctx.taxIncome().adjustedFloors(), discretionaryByYear, years, trialCount,
                initTotalBalance, medianBalanceByYear, maxAdjRate);

        int successCount = 0;
        for (int t = 0; t < trialCount; t++) {
            var simConfig = buildSimConfig(ctx, t, poolSetup, conversionByYear, conversionTaxByYear, false, adaptation);
            var result = trialSimulator.simulateTrial(
                    ctx.taxIncome().incomeByYear(), ctx.taxIncome().surplusTaxByYear(),
                    ctx.taxIncome().adjustedFloors(), discretionaryByYear, years, simConfig);
            if (result.success()) {
                successCount++;
            }
        }
        return toBD((double) successCount / trialCount);
    }

    // UseVarargs: the double[] params are per-year indexed arrays, not a variable argument
    // list — varargs would change the call contract and invite accidental misuse.
    @SuppressWarnings("PMD.UseVarargs")
    private List<GuardrailYearlySpending> buildYearlySpending(OptimizationSetup ctx,
                                                              GuardrailOptimizationInput input,
                                                              double[] discretionaryByYear,
                                                              double[][] corridors,
                                                              double[] medianBalanceByYear,
                                                              double[] p10BalanceByYear,
                                                              double[] p25BalanceByYear) {
        var yearlySpending = new ArrayList<GuardrailYearlySpending>();
        for (int y = 0; y < ctx.sim().years(); y++) {
            int age = ctx.sim().retirementAge() + y;
            int calendarYear = ctx.sim().retirementYear() + y;
            double floor = ctx.taxIncome().adjustedFloors()[y];
            double disc = discretionaryByYear[y];
            double recommended = floor + disc;
            double income = ctx.taxIncome().incomeByYear()[y];
            double withdrawal = Math.max(0, recommended - income);
            String phaseName = findPhaseName(input.phases(), age);

            yearlySpending.add(new GuardrailYearlySpending(
                    calendarYear, age,
                    toBD(recommended), toBD(corridors[0][y]), toBD(corridors[1][y]),
                    toBD(floor), toBD(disc), toBD(income), toBD(withdrawal), phaseName,
                    toBD(medianBalanceByYear[y]),
                    toBD(p10BalanceByYear[y]), toBD(p25BalanceByYear[y])));
        }
        return yearlySpending;
    }

    private RothConversionScheduleResponse buildConvScheduleResponse(
            OptimizationSetup ctx,
            GuardrailOptimizationInput input,
            RothConversionOptimizer.RothConversionSchedule convSchedule,
            double mcExhaustionPct) {
        if (convSchedule == null) {
            return null;
        }
        var convYears = new ArrayList<ConversionYearDetail>();
        for (int y = 0; y < ctx.sim().years(); y++) {
            int age = ctx.sim().retirementAge() + y;
            int calendarYear = ctx.sim().retirementYear() + y;
            if (convSchedule.conversionByYear()[y] > 0) {
                convYears.add(new ConversionYearDetail(
                        calendarYear, age,
                        toBD(convSchedule.conversionByYear()[y]),
                        toBD(convSchedule.conversionTaxByYear()[y]),
                        toBD(convSchedule.traditionalBalance()[y]),
                        toBD(convSchedule.rothBalance()[y]),
                        toBD(convSchedule.projectedRmd()[y]),
                        toBD(ctx.taxIncome().incomeByYear()[y]),
                        toBD(ctx.taxIncome().taxableIncomeByYear()[y]
                                + convSchedule.conversionByYear()[y]),
                        null));
            }
        }
        return new RothConversionScheduleResponse(
                toBD(convSchedule.lifetimeTaxWith()),
                toBD(convSchedule.lifetimeTaxWithout()),
                toBD(convSchedule.lifetimeTaxWithout() - convSchedule.lifetimeTaxWith()),
                convSchedule.exhaustionAge(),
                convSchedule.exhaustionTargetMet(),
                input.conversionBracketRate(),
                input.rmdTargetBracketRate(),
                input.traditionalExhaustionBuffer(),
                toBD(mcExhaustionPct),
                toBD(convSchedule.targetTraditionalBalance()),
                input.rmdBracketHeadroom() != null
                        ? input.rmdBracketHeadroom() : new BigDecimal("0.10"),
                convYears);
    }

    private static String findPhaseName(List<GuardrailPhaseInput> phases, int age) {
        if (phases == null || phases.isEmpty()) {
            return "Retirement";
        }
        for (var phase : phases) {
            if (age >= phase.startAge()
                    && (phase.endAge() == null || age <= phase.endAge())) {
                return phase.name();
            }
        }
        return "Retirement";
    }

    private static double percentile(double[] sorted, double p) {
        return PercentileCalculator.percentile(sorted, p);
    }

    private static BigDecimal toBD(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, ROUNDING);
    }
}
