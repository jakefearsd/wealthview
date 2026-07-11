package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.SocialSecurityTaxCalculator;

/**
 * Builds the {@link OptimizationSetup} for a guardrail run from the raw
 * {@link GuardrailOptimizationInput}: pool balances, Monte Carlo portfolio paths, deterministic
 * per-year income, rental-aware taxable income, marginal rates, dynamic-sequencing bracket
 * ceilings, and the inflation-adjusted essential floor. Extracted from
 * {@link MonteCarloSpendingOptimizer} to isolate context preparation from the optimization itself.
 */
final class OptimizationContextBuilder {

    private static final SocialSecurityTaxCalculator SS_TAX_CALCULATOR = new SocialSecurityTaxCalculator();

    @Nullable
    private final FederalTaxCalculator taxCalculator;
    @Nullable
    private final CapitalGainsTaxCalculator capitalGainsTaxCalculator;

    /** Convenience constructor without a capital-gains calculator (taxable pool realizes no LTCG tax). */
    OptimizationContextBuilder(@Nullable FederalTaxCalculator taxCalculator) {
        this(taxCalculator, null);
    }

    OptimizationContextBuilder(@Nullable FederalTaxCalculator taxCalculator,
                               @Nullable CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
        this.taxCalculator = taxCalculator;
        this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
    }

    /** Pre-computed per-year income and tax arrays for the optimization run. */
    private record IncomeArrays(double[] incomeByYear, double[] taxableIncomeByYear,
                                double[] surplusTaxByYear) {}

    OptimizationSetup build(GuardrailOptimizationInput input, RealReturnMatrix matrix) {
        int retirementYear = input.retirementDate().getYear();
        int retirementAge = retirementYear - input.birthYear();
        int endAge = input.endAge();
        int years = endAge - retirementAge;
        int rmdStartAge = RmdCalculator.rmdStartAge(input.birthYear());

        if (years <= 0) {
            return new OptimizationSetup(
                    new PortfolioSetup(0, 0, 0, 0, null, 0, 0, 0, 0, 0),
                    new SimulationParameters(retirementYear, retirementAge, endAge, years, 0, 0, 0,
                            null, null, null, null, rmdStartAge, 0, 0),
                    new TaxIncomeContext(null, 0, null, null, null, null, null, null, null, null, null, null));
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

        double inflationRate = input.inflationRate() != null
                ? input.inflationRate().doubleValue() : 0.0;

        // Run MC trials (no withdrawals) to get per-pool return sequences plus the blended
        // total-portfolio path. Each account's real return comes from a fixed override or its
        // allocation blended against the capital-market matrix (shared index sequence per trial);
        // per-pool returns are balance-weighted. The projection is REAL terms, so pools grow at
        // these real returns directly (no Fisher conversion), matching the constant-real
        // spending/income model.
        PoolReturnModel returnModel = PoolReturnModel.from(input.accounts(), inflationRate);
        double feeRate = resolveFeeRate(input);
        PortfolioReturnPaths returnPaths = PortfolioPathGenerator.generate(
                trialCount, years, returnModel, matrix, rng, feeRate);
        double[][] portfolioPaths = returnPaths.portfolioPaths();

        // Compute deterministic income for each year (real terms: deflated by scenario inflation)
        IncomeYearData[] incomeData = IncomeProjector.computeDeterministic(
                input.incomeSources(), retirementAge, years, inflationRate);
        FilingStatus filingStatus = input.filingStatus() != null
                ? FilingStatus.fromString(input.filingStatus()) : FilingStatus.SINGLE;

        // Audit B2 (MC alignment): computeDeterministic treats Social Security as 100% taxable; replace
        // that with the IRS two-tier taxable SHARE so the MC's income base matches the deterministic
        // engine's direction rather than over-taxing Social Security.
        incomeData = applySocialSecurityTaxableShare(incomeData, input.incomeSources(),
                retirementAge, years, essentialFloor, filingStatus, inflationRate);

        var incomeArrays = computeIncomeArrays(incomeData, years, retirementYear, filingStatus);

        // Compute rental-aware taxable income for marginal rate pre-computation.
        // This adjusts the base taxable income with rental property depreciation,
        // passive loss rules, and carryforward so that MC trial withdrawal tax
        // estimates reflect actual bracket positions.
        double[] rentalAwareTaxableIncome = IncomeProjector.computeRentalAwareTaxable(
                incomeArrays.taxableIncomeByYear(), input.incomeSources(),
                retirementAge, input.birthYear(), years);

        // Verify essential floor feasibility (constant real)
        double[] adjustedFloors = SustainabilitySearch.verifyEssentialFloor(
                portfolioPaths, incomeArrays.incomeByYear(), essentialFloor,
                confidenceLevel, years, trialCount);

        double[] marginalRates = MarginalRateCalculator.compute(
                taxCalculator, rentalAwareTaxableIncome, retirementYear, years, filingStatus);
        TaxContext taxCtx = (initTraditional > 0 || initRoth > 0)
                ? new TaxContext(initTaxable, initTraditional, initRoth,
                        withdrawalOrder, marginalRates)
                : null;

        double[] dsBracketCeilingByYear = computeDsBracketCeilings(
                withdrawalOrder, input.dynamicSequencingBracketRate(),
                years, retirementYear, filingStatus);

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        // Capital-gains taxation inputs for the taxable pool's FIFO lots (Task 6):
        //  - initTaxableBasis seeds the initial lot's cost basis (embedded gain = balance - basis);
        //  - ltcgRateByYear is the per-year marginal LTCG rate probed from the year's ordinary income;
        //  - dividendYield comes from the scenario's params_json (same field the deterministic engine
        //    reads via ScenarioParamsParser.dividendYield), falling back to the same default when unset.
        double initTaxableBasis = sumBasisByType(input.accounts(), PoolStrategy.POOL_TAXABLE);
        double[] ltcgRateByYear = LtcgRateCalculator.compute(
                capitalGainsTaxCalculator, taxCalculator, rentalAwareTaxableIncome, retirementYear, years,
                filingStatus, inflationRate);
        double dividendYield = resolveDividendYield(input);

        return new OptimizationSetup(
                new PortfolioSetup(initTaxable, initTraditional, initRoth,
                        initialPortfolio, withdrawalOrder, cashReserveYears, cashReturnRate,
                        terminalTarget, portfolioFloor, initTaxableBasis),
                new SimulationParameters(retirementYear, retirementAge, endAge, years,
                        trialCount, confidenceLevel, inflationRate, portfolioPaths,
                        returnPaths.taxableReturns(), returnPaths.traditionalReturns(),
                        returnPaths.rothReturns(), rmdStartAge, dividendYield, feeRate),
                new TaxIncomeContext(filingStatus, essentialFloor,
                        incomeArrays.incomeByYear(), incomeArrays.taxableIncomeByYear(),
                        incomeArrays.surplusTaxByYear(),
                        incomeData, rentalAwareTaxableIncome, adjustedFloors, marginalRates,
                        taxCtx, dsBracketCeilingByYear, ltcgRateByYear));
    }

    /**
     * Replaces the 100%-taxable Social Security figure baked into {@code incomeData} by
     * {@link IncomeProjector#computeDeterministic} with the IRS two-tier taxable SHARE (audit B2).
     *
     * <p>For each year the Social Security provisional income uses non-SS taxable income plus the
     * year's EXPECTED portfolio draw (essential floor − total income, floored at 0) as the ordinary
     * base. This is a single-pass approximation: unlike the deterministic engine's fixed-point loop,
     * the expected draw is a fixed deterministic quantity that does not depend on how much Social
     * Security is taxable, so no iteration is needed. The whole draw is treated as ordinary income
     * (an upper-bound approximation — some of it would come from Roth/taxable pools), and the
     * fixed-nominal thresholds are deflated on the retirement-anchored clock the MC already uses for
     * income and bracket ceilings. Years with no Social Security benefit are returned unchanged, so
     * the hot loop and non-SS scenarios are untouched.
     */
    private IncomeYearData[] applySocialSecurityTaxableShare(
            IncomeYearData[] incomeData, List<ProjectionIncomeSourceInput> sources,
            int retirementAge, int years, double essentialFloor, FilingStatus filingStatus,
            double inflationRate) {
        double[] ssBenefitByYear = IncomeProjector.socialSecurityBenefitByYear(
                sources, retirementAge, years, inflationRate);
        BigDecimal inflationBd = BigDecimal.valueOf(inflationRate);
        IncomeYearData[] adjusted = new IncomeYearData[years];
        for (int y = 0; y < years; y++) {
            double ssBenefit = ssBenefitByYear[y];
            if (ssBenefit <= 0) {
                adjusted[y] = incomeData[y];
                continue;
            }
            double total = incomeData[y].totalIncome();
            double nonSsTaxable = Math.max(0, incomeData[y].taxableIncome() - ssBenefit);
            double expectedDraw = Math.max(0, essentialFloor - total);
            double provisionalOther = nonSsTaxable + expectedDraw;
            double ssTaxable = SS_TAX_CALCULATOR.computeTaxableAmount(
                    BigDecimal.valueOf(ssBenefit), BigDecimal.valueOf(provisionalOther),
                    filingStatus.value(), y, inflationBd).doubleValue();
            adjusted[y] = new IncomeYearData(total, nonSsTaxable + ssTaxable);
        }
        return adjusted;
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
                                              FilingStatus filingStatus) {
        if (!PoolStrategy.WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING.equals(withdrawalOrder)
                || dynamicSequencingBracketRate == null
                || taxCalculator == null) {
            return null;
        }
        double[] ceilings = new double[years];
        for (int y = 0; y < years; y++) {
            // Real-terms: brackets are constant real, so the ceiling is NOT inflation-indexed.
            ceilings[y] = taxCalculator.computeMaxIncomeForBracket(
                    dynamicSequencingBracketRate, retirementYear + y, filingStatus,
                    BigDecimal.ZERO).doubleValue();
        }
        return ceilings;
    }

    /**
     * Resolves the scenario's dividend yield for the MC engine, falling back to the same default
     * the deterministic engine uses (see {@link ScenarioParamsParser#DEFAULT_DIVIDEND_YIELD}) when
     * the scenario's {@code params_json} doesn't set one. Extracted to its own method (rather than
     * an inline ternary in {@link #build}) to keep that method's NPath complexity in check.
     */
    private static double resolveDividendYield(GuardrailOptimizationInput input) {
        return input.dividendYield() != null
                ? input.dividendYield().doubleValue() : ScenarioParamsParser.DEFAULT_DIVIDEND_YIELD.doubleValue();
    }

    /**
     * Resolves the scenario's fee rate for the MC engine, falling back to the same default the
     * deterministic engine uses (see {@link ScenarioParamsParser#DEFAULT_FEE_RATE}) when the
     * scenario's {@code params_json} doesn't set one (audit B1).
     */
    private static double resolveFeeRate(GuardrailOptimizationInput input) {
        return input.feeRate() != null
                ? input.feeRate().doubleValue() : ScenarioParamsParser.DEFAULT_FEE_RATE.doubleValue();
    }

    private static double sumByType(List<? extends ProjectionAccountInput> accounts, String type) {
        return accounts.stream()
                .filter(a -> type.equals(a.accountType()))
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }

    private static double sumBasisByType(List<? extends ProjectionAccountInput> accounts, String type) {
        return accounts.stream()
                .filter(a -> type.equals(a.accountType()))
                .mapToDouble(a -> a.costBasis().doubleValue())
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
