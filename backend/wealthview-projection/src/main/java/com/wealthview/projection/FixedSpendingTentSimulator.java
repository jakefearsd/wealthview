package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.wealthview.core.projection.BondMaturityReturnsProvider;
import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.BondTentSimulator;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.BondTentSimulation;
import com.wealthview.core.projection.dto.BondTentSimulationColumn;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingPlan;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import io.micrometer.observation.annotation.Observed;

/**
 * Runs the scenario's current spending plan, held fixed, through the Monte Carlo trials once with no tent and once
 * per tent mix. One context is built with the scenario's own tent, so every column runs on the same market paths and
 * the same seed and the columns differ only in the tent.
 *
 * <p>A trial succeeds when the plan is paid in full every year: the whole schedule is passed as the floors and the
 * discretionary part is zero. That is stricter than the optimizer's essential-floor metric on purpose, since here
 * the plan is the requirement. A guardrail plan's stored Roth conversions are replayed; a tier plan runs without
 * any, as Monte Carlo has never modeled the deterministic Roth settings.
 */
@Component
public class FixedSpendingTentSimulator implements BondTentSimulator {

    private static final String NO_TENT = "No tent";
    private static final int RATE_SCALE = 4;
    private static final int YEARS_SCALE = 2;
    private static final int MONEY_SCALE = 2;
    private static final int BALANCE_CHECK_AGE = 89;
    private static final double WORST_QUANTILE = 0.10;
    private static final double MEDIAN_QUANTILE = 0.50;

    @Nullable
    private final CapitalMarketAssumptionsProvider capitalMarketAssumptions;
    @Nullable
    private final RealReturnMatrix presetMatrix;
    @Nullable
    private final BondMaturityReturnsProvider bondMaturityReturns;
    @Nullable
    private final MaturityReturnMatrix presetMaturities;
    private final ScenarioParamsParser paramsParser = new ScenarioParamsParser();
    private final TrialPassRunner passRunner = new TrialPassRunner(new TrialSimulator());
    private final OptimizationContextBuilder contextBuilder;

    /** Test constructor: supplies the return matrix and its aligned bond maturity returns directly. */
    public FixedSpendingTentSimulator(@Nullable FederalTaxCalculator taxCalculator, RealReturnMatrix matrix,
                                      MaturityReturnMatrix maturities) {
        this(taxCalculator, null, null, null, matrix, maturities);
    }

    @Autowired
    public FixedSpendingTentSimulator(@Nullable FederalTaxCalculator taxCalculator,
                                      CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                      @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                      BondMaturityReturnsProvider bondMaturityReturns) {
        this(taxCalculator, capitalMarketAssumptions, capitalGainsTaxCalculator, bondMaturityReturns, null, null);
    }

    private FixedSpendingTentSimulator(@Nullable FederalTaxCalculator taxCalculator,
                                       @Nullable CapitalMarketAssumptionsProvider capitalMarketAssumptions,
                                       @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator,
                                       @Nullable BondMaturityReturnsProvider bondMaturityReturns,
                                       @Nullable RealReturnMatrix presetMatrix,
                                       @Nullable MaturityReturnMatrix presetMaturities) {
        this.capitalMarketAssumptions = capitalMarketAssumptions;
        this.bondMaturityReturns = bondMaturityReturns;
        this.presetMatrix = presetMatrix;
        this.presetMaturities = presetMaturities;
        this.contextBuilder = new OptimizationContextBuilder(taxCalculator, capitalGainsTaxCalculator);
    }

    @Observed(name = "wealthview.mc.bond_tent_simulation",
              contextualName = "monte-carlo-bond-tent-simulation",
              lowCardinalityKeyValues = {"component", "projection"})
    @Override
    public BondTentSimulation simulate(GuardrailOptimizationInput input, ProjectionInput projectionInput) {
        SpendingPlan plan = planOf(projectionInput);
        BondTentConfig tent = input.bondTent();
        if (plan == null || tent == null) {
            throw new IllegalArgumentException("A bond tent analysis needs a spending plan and a bond tent");
        }
        // A bond tent forces the default 1972-2025 window, as the optimizer does.
        RealReturnMatrix matrix = presetMatrix != null ? presetMatrix
                : Objects.requireNonNull(capitalMarketAssumptions, "capitalMarketAssumptions required").matrix(false);
        MaturityReturnMatrix maturities = presetMaturities != null ? presetMaturities
                : Objects.requireNonNull(bondMaturityReturns, "bondMaturityReturns required").alignedTo(matrix);

        int retirementYear = input.retirementDate().getYear();
        int retirementAge = retirementYear - input.birthYear();
        // The context's expected-path tax estimate reads the essential floor, so it is the first year's spending.
        BigDecimal firstYear = plan.resolveYear(retirementYear, retirementAge, 1, input.inflationRate(),
                BigDecimal.ZERO).totalSpending();
        var seeded = PreRetirementAccumulation.seedAtRetirement(input, matrix).withEssentialFloor(firstYear);
        var ctx = contextBuilder.build(seeded, input.accounts(), matrix, maturities);
        int years = ctx.sim().years();
        if (years <= 0) {
            return new BondTentSimulation(0, plannedYears(tent), List.of());
        }

        var schedule = FixedSpendingSchedule.of(plan, retirementYear, retirementAge, years, input.inflationRate(),
                ctx.sim().household(), ctx.sim().survivorSpendingFactor());
        double[] conversionByYear = schedule.conversionByYear();
        double[] conversionTaxByYear = conversionByYear == null ? null : new double[years];
        var poolSetup = PoolSimSetup.resolve(ctx.portfolio(), conversionByYear);
        var runner = new ColumnRunner(ctx, poolSetup, schedule, conversionTaxByYear, retirementAge, passRunner);

        var columns = new ArrayList<BondTentSimulationColumn>();
        columns.add(runner.column(NO_TENT, null, null));
        double inflation = ctx.sim().inflationRate();
        // The context was built with the scenario's tent, so its plan and recorded paths exist.
        var scenarioTent = Objects.requireNonNull(ctx.sim().tentPlan(), "tent plan");
        for (BondTentMix mix : tent.mixes()) {
            columns.add(runner.column(mix.name(), scenarioTent.withMix(mix, tent.yields(), inflation),
                    ctx.sim().tentPaths()));
        }
        return new BondTentSimulation(ctx.sim().trialCount(), plannedYears(tent), columns);
    }

    @Nullable
    private SpendingPlan planOf(ProjectionInput projectionInput) {
        if (projectionInput.guardrailSpending() != null) {
            return projectionInput.guardrailSpending();
        }
        return paramsParser.parseTierBasedPlan(projectionInput.spendingProfile());
    }

    private static BigDecimal plannedYears(BondTentConfig tent) {
        return tent.coverageYears().setScale(YEARS_SCALE, RoundingMode.HALF_UP);
    }

    /** Everything one column's pass needs besides its tent; the schedule's conversion tax is all zero. */
    private record ColumnRunner(OptimizationSetup ctx, PoolSimSetup poolSetup, FixedSpendingSchedule schedule,
                                @Nullable double[] conversionTaxByYear, int retirementAge,
                                TrialPassRunner passRunner) {

        BondTentSimulationColumn column(String name, @Nullable TrialTentPlan plan, @Nullable TentPaths paths) {
            double[] spending = schedule.spending();
            double[] discretionary = new double[spending.length];
            var factory = GuardrailResponseBuilder
                    .configFactoryBuilder(ctx, poolSetup, schedule.conversionByYear(), conversionTaxByYear)
                    .tent(plan, paths).build();
            double[] income = ctx.taxIncome().incomeByYear();
            double[] surplusTax = ctx.taxIncome().surplusTaxByYear();
            var pass = passRunner.run(ctx.sim().trialCount(), ctx.sim().years(), factory, income, surplusTax,
                    spending, discretionary, null, true, null);
            BigDecimal target = plan == null ? null
                    : money(factory.tentSizingFor(spending, discretionary, income, surplusTax).target());
            double[] covered = Arrays.stream(pass.tentYearsCovered()).filter(v -> !Double.isNaN(v)).sorted()
                    .toArray();
            boolean hasCoverage = plan != null && covered.length > 0;
            return new BondTentSimulationColumn(name, target,
                    BigDecimal.valueOf((double) pass.successCount() / ctx.sim().trialCount())
                            .setScale(RATE_SCALE, RoundingMode.HALF_UP),
                    hasCoverage ? years(PercentileCalculator.percentile(covered, MEDIAN_QUANTILE)) : null,
                    hasCoverage ? years(PercentileCalculator.percentile(covered, WORST_QUANTILE)) : null,
                    balanceAt90(pass.yearBalances()));
        }

        /** The median end balance of the year the primary turns 89, or null when the horizon ends first. */
        // UseVarargs: the year-major balance grid is not a variable-length argument list.
        @SuppressWarnings("PMD.UseVarargs")
        @Nullable
        private BigDecimal balanceAt90(double[][] yearBalances) {
            int index = BALANCE_CHECK_AGE - retirementAge;
            if (index < 0 || index >= yearBalances.length) {
                return null;
            }
            double[] sorted = yearBalances[index].clone();
            Arrays.sort(sorted);
            return money(PercentileCalculator.percentile(sorted, MEDIAN_QUANTILE));
        }
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal years(double value) {
        return BigDecimal.valueOf(value).setScale(YEARS_SCALE, RoundingMode.HALF_UP);
    }
}
