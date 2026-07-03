package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.wealthview.core.projection.dto.ConversionYearDetail;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.RothConversionScheduleResponse;

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

    private final TrialSimulator trialSimulator;

    GuardrailResponseBuilder(TrialSimulator trialSimulator) {
        this.trialSimulator = trialSimulator;
    }

    // NPathComplexity: response assembly fans out over many independent optional fields; the
    // path count is multiplicative but each branch is a trivial null/empty guard, so the method
    // is far simpler than its NPath number suggests. Splitting it would only scatter the mapping.
    @SuppressWarnings("PMD.NPathComplexity")
    GuardrailProfileResponse build(OptimizationSetup ctx,
                                   GuardrailOptimizationInput input,
                                   double[] discretionaryByYear,
                                   double[] conversionByYear,
                                   double[] conversionTaxByYear,
                                   RothConversionOptimizer.RothConversionSchedule convSchedule) {
        // Compute corridors + corridor smoothing
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
        boolean simPools = conversionByYear != null
                || ctx.portfolio().initTraditional() > 0 || ctx.portfolio().initRoth() > 0;
        double initTaxable = simPools
                ? ctx.portfolio().initTaxable() : ctx.portfolio().initialPortfolio();
        double initTraditional = simPools ? ctx.portfolio().initTraditional() : 0;
        double initRoth = simPools ? ctx.portfolio().initRoth() : 0;
        String order = simPools && ctx.portfolio().withdrawalOrder() != null
                ? ctx.portfolio().withdrawalOrder() : "taxable_first";

        // marginalRateByYear is null — buildResponse does not model withdrawal tax
        var simConfig = new TrialSimulator.SimulationConfig(
                initTaxable, initTraditional, initRoth, order, null,
                conversionByYear, conversionTaxByYear, ctx.sim().retirementAge(),
                ctx.taxIncome().dsBracketCeilingByYear(),
                ctx.portfolio().cashReserveYears(), ctx.portfolio().cashReturnRate(), true);

        double[] historicalReturns = HistoricalReturns.getReturns();
        var rng2 = input.seed() != null ? new Random(input.seed()) : new Random();
        double[][] yearBalances = new double[ctx.sim().years()][ctx.sim().trialCount()];
        double[] finalBalances = new double[ctx.sim().trialCount()];
        int tradExhaustedCount = 0;
        for (int t = 0; t < ctx.sim().trialCount(); t++) {
            double[] nominalReturns = PortfolioPathGenerator.generateNominalReturns(
                    ctx.sim().years(), historicalReturns, rng2, ctx.sim().inflationRate());

            var result = trialSimulator.simulateTrial(nominalReturns,
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
        long failures = Arrays.stream(finalBalances).filter(b -> b <= 0).count();
        double failureRate = (double) failures / ctx.sim().trialCount();

        var yearlySpending = buildYearlySpending(ctx, input, discretionaryByYear, corridors,
                medianBalanceByYear, p10BalanceByYear, p25BalanceByYear);

        log.info("MC optimization complete: {} trials, {} years, median final balance {}",
                ctx.sim().trialCount(), ctx.sim().years(), toBD(medianFinal));

        RothConversionScheduleResponse convScheduleResponse =
                buildConvScheduleResponse(ctx, input, convSchedule, mcExhaustionPct);

        return new GuardrailProfileResponse(
                null, null, "Optimized",
                input.essentialFloor(), input.terminalBalanceTarget(),
                input.returnMean(),
                ctx.sim().trialCount(), input.confidenceLevel(),
                input.phases(), yearlySpending,
                toBD(medianFinal), toBD(failureRate),
                toBD(p10Final),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                input.portfolioFloor(), input.maxAnnualAdjustmentRate(),
                input.phaseBlendYears(), null,
                input.cashReserveYears(), input.cashReturnRate(),
                convScheduleResponse);
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
