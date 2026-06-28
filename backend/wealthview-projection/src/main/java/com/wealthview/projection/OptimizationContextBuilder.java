package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Builds the {@link OptimizationSetup} for a guardrail run from the raw
 * {@link GuardrailOptimizationInput}: pool balances, Monte Carlo portfolio paths, deterministic
 * per-year income, rental-aware taxable income, marginal rates, dynamic-sequencing bracket
 * ceilings, and the inflation-adjusted essential floor. Extracted from
 * {@link MonteCarloSpendingOptimizer} to isolate context preparation from the optimization itself.
 */
final class OptimizationContextBuilder {

    @Nullable
    private final FederalTaxCalculator taxCalculator;

    OptimizationContextBuilder(@Nullable FederalTaxCalculator taxCalculator) {
        this.taxCalculator = taxCalculator;
    }

    /** Pre-computed per-year income and tax arrays for the optimization run. */
    private record IncomeArrays(double[] incomeByYear, double[] taxableIncomeByYear,
                                double[] surplusTaxByYear) {}

    OptimizationSetup build(GuardrailOptimizationInput input) {
        int retirementYear = input.retirementDate().getYear();
        int retirementAge = retirementYear - input.birthYear();
        int endAge = input.endAge();
        int years = endAge - retirementAge;

        if (years <= 0) {
            return new OptimizationSetup(
                    new PortfolioSetup(0, 0, 0, 0, null, 0, 0, 0, 0),
                    new SimulationParameters(retirementYear, retirementAge, endAge, years, 0, 0, 0, null),
                    new TaxIncomeContext(null, 0, null, null, null, null, null, null, null, null, null));
        }

        int trialCount = input.trialCount();
        double initialPortfolio = totalPortfolio(input.accounts());
        double initTaxable = sumByType(input.accounts(), PoolStrategy.POOL_TAXABLE);
        double initTraditional = sumByType(input.accounts(), PoolStrategy.POOL_TRADITIONAL);
        double initRoth = sumByType(input.accounts(), PoolStrategy.POOL_ROTH);
        String withdrawalOrder = input.withdrawalOrder() != null ? input.withdrawalOrder() : "taxable_first";
        double essentialFloor = input.essentialFloor().doubleValue();
        double terminalTarget = input.terminalBalanceTarget().doubleValue();
        double confidenceLevel = input.confidenceLevel().doubleValue();

        int cashReserveYears = input.cashReserveYears();
        double cashReturnRate = input.cashReturnRate() != null
                ? input.cashReturnRate().doubleValue() : 0.0;

        Random rng = input.seed() != null ? new Random(input.seed()) : new Random();

        double[] historicalReturns = HistoricalReturns.getReturns();

        double inflationRate = input.inflationRate() != null
                ? input.inflationRate().doubleValue() : 0.0;

        // Run MC trials (no withdrawals) to get portfolio trajectories using bootstrap.
        // Bootstrap returns are real (CPI-adjusted); convert to nominal via Fisher equation
        // so portfolio growth matches the nominal spending/income model.
        double[][] portfolioPaths = PortfolioPathGenerator.generatePaths(
                trialCount, years, initialPortfolio, historicalReturns, rng, inflationRate);

        // Compute deterministic income for each year
        IncomeYearData[] incomeData = IncomeProjector.computeDeterministic(
                input.incomeSources(), retirementAge, years);
        FilingStatus filingStatus = input.filingStatus() != null
                ? FilingStatus.fromString(input.filingStatus()) : FilingStatus.SINGLE;

        var incomeArrays = computeIncomeArrays(incomeData, years, retirementYear, filingStatus);

        // Compute rental-aware taxable income for marginal rate pre-computation.
        // This adjusts the base taxable income with rental property depreciation,
        // passive loss rules, and carryforward so that MC trial withdrawal tax
        // estimates reflect actual bracket positions.
        double[] rentalAwareTaxableIncome = IncomeProjector.computeRentalAwareTaxable(
                incomeArrays.taxableIncomeByYear(), input.incomeSources(),
                retirementAge, input.birthYear(), years);

        // Verify essential floor feasibility (inflation-adjusted)
        double[] adjustedFloors = SustainabilitySearch.verifyEssentialFloor(
                portfolioPaths, incomeArrays.incomeByYear(), essentialFloor,
                confidenceLevel, years, trialCount, inflationRate);

        double[] marginalRates = MarginalRateCalculator.compute(
                taxCalculator, rentalAwareTaxableIncome, retirementYear, years, filingStatus);
        TaxContext taxCtx = (initTraditional > 0 || initRoth > 0)
                ? new TaxContext(initTaxable, initTraditional, initRoth,
                        withdrawalOrder, marginalRates)
                : null;

        double[] dsBracketCeilingByYear = computeDsBracketCeilings(
                withdrawalOrder, input.dynamicSequencingBracketRate(),
                years, retirementYear, filingStatus, input.inflationRate());

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        return new OptimizationSetup(
                new PortfolioSetup(initTaxable, initTraditional, initRoth,
                        initialPortfolio, withdrawalOrder, cashReserveYears, cashReturnRate,
                        terminalTarget, portfolioFloor),
                new SimulationParameters(retirementYear, retirementAge, endAge, years,
                        trialCount, confidenceLevel, inflationRate, portfolioPaths),
                new TaxIncomeContext(filingStatus, essentialFloor,
                        incomeArrays.incomeByYear(), incomeArrays.taxableIncomeByYear(),
                        incomeArrays.surplusTaxByYear(),
                        incomeData, rentalAwareTaxableIncome, adjustedFloors, marginalRates,
                        taxCtx, dsBracketCeilingByYear));
    }

    private IncomeArrays computeIncomeArrays(IncomeYearData[] incomeData, int years,
                                             int retirementYear, FilingStatus filingStatus) {
        double[] incomeByYear = new double[years];
        double[] taxableIncomeByYear = new double[years];
        double[] surplusTaxByYear = new double[years];
        for (int y = 0; y < years; y++) {
            incomeByYear[y] = incomeData[y].totalIncome();
            taxableIncomeByYear[y] = incomeData[y].taxableIncome();
            surplusTaxByYear[y] = computeSurplusTax(
                    incomeData[y].taxableIncome(), retirementYear + y, filingStatus);
        }
        return new IncomeArrays(incomeByYear, taxableIncomeByYear, surplusTaxByYear);
    }

    // ReturnEmptyCollectionRatherThanNull: the return type is a primitive double[] sentinel, not a
    // Collection — null signals "no dynamic-sequencing ceilings apply" and callers null-check it.
    @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
    private double[] computeDsBracketCeilings(String withdrawalOrder,
                                              BigDecimal dynamicSequencingBracketRate,
                                              int years, int retirementYear,
                                              FilingStatus filingStatus,
                                              BigDecimal inflationRate) {
        if (!PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(withdrawalOrder)
                || dynamicSequencingBracketRate == null
                || taxCalculator == null) {
            return null;
        }
        double[] ceilings = new double[years];
        for (int y = 0; y < years; y++) {
            ceilings[y] = taxCalculator.computeMaxIncomeForBracket(
                    dynamicSequencingBracketRate, retirementYear + y, filingStatus,
                    inflationRate).doubleValue();
        }
        return ceilings;
    }

    private static double sumByType(List<? extends ProjectionAccountInput> accounts, String type) {
        return accounts.stream()
                .filter(a -> type.equals(a.accountType()))
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }

    private double computeSurplusTax(double taxableIncome, int taxYear, FilingStatus filingStatus) {
        if (taxCalculator == null || taxableIncome <= 0) {
            return 0.0;
        }
        BigDecimal tax = taxCalculator.computeTax(
                BigDecimal.valueOf(taxableIncome), taxYear, filingStatus);
        return tax.doubleValue();
    }

    private double totalPortfolio(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }
}
