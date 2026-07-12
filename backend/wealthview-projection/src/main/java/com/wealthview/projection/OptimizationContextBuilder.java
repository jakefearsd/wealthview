package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
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
                            null, null, null, null, rmdStartAge, 0, 0, 0, 0, 1, 1.0, null),
                    new TaxIncomeContext(null, 0, null, null, null, null, null, null, null, null, null, null, null));
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

        // Audit C7 / T7-M3: the MC engine only models years from retirement onward, so its own
        // year index is retirement-anchored. This offset converts it to the SAME calendar-anchored
        // clock (taxYear - baseYear) the deterministic engine's income deflation and Social
        // Security threshold deflator both use -- see IncomeProjector.computeDeterministic.
        int retirementYearOffsetFromBase = retirementYear - input.baseYear();

        FilingStatus filingStatus = input.filingStatus() != null
                ? FilingStatus.fromString(input.filingStatus()) : FilingStatus.SINGLE;

        // Household task 6: resolve the first-death transition inputs. Single-person (spouse absent)
        // ⇒ every branch below reduces to the pre-household path bit-for-bit (the byte-identical
        // anchor). preStatus (filingStatus) is the both-alive filing status; from the transition year
        // the household files SINGLE.
        var household = HouseholdMcResolver.resolve(input, retirementYear, years, inflationRate, filingStatus);
        int transitionIdx = household.inWindowTransitionIdx();

        // Both-alive income pipeline, spliced with the survivor phase from an in-window first death
        // (extracted so build() stays under the PMD NPath threshold — the no-regression policy).
        var income = resolveIncomeArrays(input, household, filingStatus, retirementAge, years,
                retirementYearOffsetFromBase, essentialFloor, retirementYear, inflationRate);
        IncomeYearData[] incomeData = income.incomeData();
        double[] incomeByYear = income.incomeByYear();
        double[] taxableIncomeByYear = income.taxableIncomeByYear();
        double[] surplusTaxByYear = income.surplusTaxByYear();
        double[] rentalAwareTaxableIncome = income.rentalAwareTaxableIncome();

        // T18a-3: the per-year net rental income contribution, isolated as the delta between the
        // rental-aware base and the crude (non-rental-aware) base taxable income. rentalAwareTaxableIncome
        // floors its aggregate at zero (IncomeProjector#computeRentalAwareTaxable), so this delta can
        // under-count in the rare year where that floor actually clips (a documented approximation;
        // see TaxContext's javadoc) -- acceptable for the NIIT surtax's secondary role in the MC engine.
        double[] rentalIncomeByYear = computeRentalIncomeDelta(
                rentalAwareTaxableIncome, taxableIncomeByYear, years);

        // Verify essential floor feasibility (constant real), then scale it by the survivor factor
        // from the transition year (household task 6) -- the single builder-side choke point every
        // downstream consumer (search, terminal/response pass, T16 adaptation corridor, T24 gate)
        // inherits. No-op for single-person / no in-window transition.
        double[] adjustedFloors = SustainabilitySearch.verifyEssentialFloor(
                portfolioPaths, incomeByYear, essentialFloor,
                confidenceLevel, years, trialCount);
        scaleSurvivorFloors(adjustedFloors, transitionIdx, household.survivorFactor(), years);

        // Household task 6: per-year exact tax tables are built MFJ (both-alive) before the transition
        // and SINGLE from it -- a construction-time filing-status switch, zero hot-loop cost.
        OrdinaryTaxTable[] ordinaryTaxTables = ordinaryTablesByYear(
                retirementYear, years, filingStatus, household, input.birthYear());
        TaxContext taxCtx = (initTraditional > 0 || initRoth > 0)
                ? new TaxContext(initTaxable, initTraditional, initRoth,
                        withdrawalOrder, ordinaryTaxTables, rentalAwareTaxableIncome, rentalIncomeByYear)
                : null;

        double[] dsBracketCeilingByYear = dsBracketCeilingsByYear(
                withdrawalOrder, input.dynamicSequencingBracketRate(),
                years, retirementYear, filingStatus, household);

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        // Capital-gains taxation inputs for the taxable pool's FIFO lots (Task 6):
        //  - initTaxableBasis seeds the initial lot's cost basis (embedded gain = balance - basis);
        //  - ltcgTaxTables (audit C5) are the per-year exact LTCG bracket tables, evaluated in the
        //    hot loop at the trial's ACTUAL ordinary stacking floor (base income + that year's
        //    conversion/draws/RMD excess) rather than a fixed probe rate;
        //  - dividendYield comes from the scenario's params_json (same field the deterministic engine
        //    reads via ScenarioParamsParser.dividendYield), falling back to the same default when unset.
        double initTaxableBasis = sumBasisByType(input.accounts(), PoolStrategy.POOL_TAXABLE);
        LtcgTaxTable[] ltcgTaxTables = ltcgTablesByYear(
                retirementYear, years, filingStatus, inflationRate, household, input.birthYear());
        double dividendYield = resolveDividendYield(input);
        double returnMean = resolveReturnMean(input, inflationRate, feeRate, matrix);
        // Audit C1: splits the MC taxable pool's yield the same way PoolStrategy.MultiPool does --
        // computed once from the taxable accounts' own allocation, falling back to 1.0 (100%
        // equity, ALL_US-equivalent -- byte-identical to pre-C1) when there are none.
        double interestYield = resolveInterestYield(input);
        double taxableEquityShare = PoolStrategy.taxableEquityShare(
                taxableAccounts(input.accounts())).doubleValue();

        return new OptimizationSetup(
                new PortfolioSetup(initTaxable, initTraditional, initRoth,
                        initialPortfolio, withdrawalOrder, cashReserveYears, cashReturnRate,
                        terminalTarget, portfolioFloor, initTaxableBasis),
                new SimulationParameters(retirementYear, retirementAge, endAge, years,
                        trialCount, confidenceLevel, inflationRate, portfolioPaths,
                        returnPaths.taxableReturns(), returnPaths.traditionalReturns(),
                        returnPaths.rothReturns(), rmdStartAge, dividendYield, feeRate, returnMean,
                        interestYield, taxableEquityShare, household.survivorFactor(), household.sim()),
                new TaxIncomeContext(filingStatus, essentialFloor,
                        incomeByYear, taxableIncomeByYear, surplusTaxByYear,
                        incomeData, rentalAwareTaxableIncome, adjustedFloors, ordinaryTaxTables,
                        taxCtx, dsBracketCeilingByYear, ltcgTaxTables, rentalIncomeByYear));
    }

    private static List<ProjectionAccountInput> taxableAccounts(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .filter(a -> PoolStrategy.POOL_TAXABLE.equals(a.accountType()))
                .toList();
    }

    /**
     * The per-year net rental income contribution isolated from the rental-aware taxable-income
     * array (T18a-3) -- see the {@link TaxContext#rentalIncomeByYear()} javadoc for the
     * floor-clamping caveat.
     */
    private static double[] computeRentalIncomeDelta(double[] rentalAwareTaxableIncome,
                                                       double[] baseTaxableIncome, int years) {
        double[] delta = new double[years];
        for (int y = 0; y < years; y++) {
            delta[y] = rentalAwareTaxableIncome[y] - baseTaxableIncome[y];
        }
        return delta;
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
     * fixed-nominal thresholds are deflated on the SAME base-year-anchored calendar clock as income
     * (audit C7 / T7-M3 — {@code retirementYearOffsetFromBase + y}, matching the deterministic
     * engine's {@code taxYear - baseYear}; previously deflated on the bare retirement-anchored
     * {@code y}, understating erosion whenever retirement starts after the base year). Years with
     * no Social Security benefit are returned unchanged, so the hot loop and non-SS scenarios are
     * untouched.
     */
    private IncomeYearData[] applySocialSecurityTaxableShare(
            IncomeYearData[] incomeData, List<ProjectionIncomeSourceInput> sources,
            int retirementAge, int years, double essentialFloor, FilingStatus filingStatus,
            double inflationRate, int retirementYearOffsetFromBase) {
        double[] ssBenefitByYear = IncomeProjector.socialSecurityBenefitByYear(
                sources, retirementAge, years, retirementYearOffsetFromBase, inflationRate);
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
            int yearsFromBase = Math.max(0, retirementYearOffsetFromBase + y);
            double ssTaxable = SS_TAX_CALCULATOR.computeTaxableAmount(
                    BigDecimal.valueOf(ssBenefit), BigDecimal.valueOf(provisionalOther),
                    filingStatus.value(), yearsFromBase, inflationBd).doubleValue();
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

    /**
     * Household task 6: resolves the per-year income arrays — the both-alive pipeline (original
     * sources, configured filing status), spliced with the survivor phase (keep-larger SS +
     * survivor_percent sources, SINGLE filing status) from an in-window first death. Single-person /
     * no-in-window-transition runs return the both-alive pipeline unchanged (the byte-identical
     * anchor). Extracted from {@link #build} to keep its NPath complexity under the PMD threshold.
     */
    private IncomePipeline resolveIncomeArrays(GuardrailOptimizationInput input,
            HouseholdMcResolver.Resolved household, FilingStatus filingStatus, int retirementAge,
            int years, int retirementYearOffsetFromBase, double essentialFloor, int retirementYear,
            double inflationRate) {
        var income = computeIncomePipeline(input.incomeSources(), filingStatus, retirementAge, years,
                retirementYearOffsetFromBase, essentialFloor, retirementYear, input.birthYear(), inflationRate);
        int transitionIdx = household.inWindowTransitionIdx();
        if (transitionIdx < 0) {
            return income;
        }
        var survivorIncome = computeIncomePipeline(household.survivorSources(), household.postStatus(),
                retirementAge, years, retirementYearOffsetFromBase, essentialFloor, retirementYear,
                input.birthYear(), inflationRate);
        return spliceIncome(income, survivorIncome, transitionIdx);
    }

    /** Household task 6: the full per-year income pipeline (deterministic income → audit-B2 Social
     * Security taxable share → income/taxable/surplus arrays → rental-aware taxable) for ONE phase
     * (a source list + filing status). Called once for the both-alive phase and, for an in-window
     * first death, again for the survivor phase; {@link #spliceIncome} joins them. */
    private IncomePipeline computeIncomePipeline(List<ProjectionIncomeSourceInput> sources,
            FilingStatus status, int retirementAge, int years, int retirementYearOffsetFromBase,
            double essentialFloor, int retirementYear, int birthYear, double inflationRate) {
        IncomeYearData[] incomeData = IncomeProjector.computeDeterministic(
                sources, retirementAge, years, retirementYearOffsetFromBase, inflationRate);
        incomeData = applySocialSecurityTaxableShare(incomeData, sources, retirementAge, years,
                essentialFloor, status, inflationRate, retirementYearOffsetFromBase);
        var arrays = computeIncomeArrays(incomeData, years, retirementYear, status);
        double[] rentalAware = IncomeProjector.computeRentalAwareTaxable(
                arrays.taxableIncomeByYear(), sources, retirementAge, birthYear, years);
        return new IncomePipeline(incomeData, arrays.incomeByYear(), arrays.taxableIncomeByYear(),
                arrays.surplusTaxByYear(), rentalAware);
    }

    /** The both-alive/survivor phase income arrays for one filing phase. */
    private record IncomePipeline(IncomeYearData[] incomeData, double[] incomeByYear,
            double[] taxableIncomeByYear, double[] surplusTaxByYear, double[] rentalAwareTaxableIncome) {}

    /** Household task 6: splices the survivor-phase income arrays into the both-alive arrays from the
     * transition year forward (each array year-indexed identically). */
    private static IncomePipeline spliceIncome(IncomePipeline pre, IncomePipeline post, int fromIdx) {
        return new IncomePipeline(
                spliceObjects(pre.incomeData(), post.incomeData(), fromIdx),
                spliceDoubles(pre.incomeByYear(), post.incomeByYear(), fromIdx),
                spliceDoubles(pre.taxableIncomeByYear(), post.taxableIncomeByYear(), fromIdx),
                spliceDoubles(pre.surplusTaxByYear(), post.surplusTaxByYear(), fromIdx),
                spliceDoubles(pre.rentalAwareTaxableIncome(), post.rentalAwareTaxableIncome(), fromIdx));
    }

    /** Household task 6: scales {@code floors[y] *= factor} from {@code transitionIdx} forward.
     * No-op when there is no in-window transition ({@code transitionIdx < 0}) or the factor is 1.0. */
    private static void scaleSurvivorFloors(double[] floors, int transitionIdx, double factor, int years) {
        if (transitionIdx < 0 || factor == 1.0) {
            return;
        }
        for (int y = transitionIdx; y < years; y++) {
            floors[y] *= factor;
        }
    }

    /** Household task 6 + 7: per-year ordinary tax tables — the both-alive filing status before the
     * transition, SINGLE from it (task 6), each built off the household's per-year filer age(s) so
     * the age-65 deduction adder applies per qualifying person while both spouses are alive and
     * filing jointly (task 7, spec §4 step 6 — see {@link OrdinaryTaxTable#computeAll(
     * FederalTaxCalculator, int, int, FilingStatus, Integer, com.wealthview.core.projection.
     * household.HouseholdContext)}). Single-person / no in-window transition returns the pre-status
     * tables unchanged (byte-identical anchor). */
    private OrdinaryTaxTable[] ordinaryTablesByYear(int retirementYear, int years,
            FilingStatus preStatus, HouseholdMcResolver.Resolved household, int birthYear) {
        OrdinaryTaxTable[] pre = OrdinaryTaxTable.computeAll(
                taxCalculator, retirementYear, years, preStatus, birthYear, household.context());
        int idx = household.inWindowTransitionIdx();
        if (idx < 0) {
            return pre;
        }
        OrdinaryTaxTable[] post = OrdinaryTaxTable.computeAll(
                taxCalculator, retirementYear, years, household.postStatus(), birthYear, household.context());
        return spliceObjects(pre, post, idx);
    }

    /** Household task 6 + 7: per-year LTCG tables spliced pre/post the transition, household-aware
     * per-person deduction (see {@link #ordinaryTablesByYear}). */
    private LtcgTaxTable[] ltcgTablesByYear(int retirementYear, int years, FilingStatus preStatus,
            double inflationRate, HouseholdMcResolver.Resolved household, int birthYear) {
        LtcgTaxTable[] pre = LtcgTaxTable.computeAll(capitalGainsTaxCalculator, taxCalculator, retirementYear,
                years, preStatus, inflationRate, birthYear, household.context());
        int idx = household.inWindowTransitionIdx();
        if (idx < 0) {
            return pre;
        }
        LtcgTaxTable[] post = LtcgTaxTable.computeAll(capitalGainsTaxCalculator, taxCalculator, retirementYear,
                years, household.postStatus(), inflationRate, birthYear, household.context());
        return spliceObjects(pre, post, idx);
    }

    /** Household task 6: per-year dynamic-sequencing bracket ceilings spliced pre/post the transition.
     * Returns null (no DS ceilings) exactly when the pre-status computation does — callers null-check it. */
    private double[] dsBracketCeilingsByYear(String withdrawalOrder, BigDecimal dynamicSequencingBracketRate,
            int years, int retirementYear, FilingStatus preStatus, HouseholdMcResolver.Resolved household) {
        double[] pre = computeDsBracketCeilings(
                withdrawalOrder, dynamicSequencingBracketRate, years, retirementYear, preStatus);
        int idx = household.inWindowTransitionIdx();
        if (idx < 0 || pre == null) {
            return pre;
        }
        double[] post = computeDsBracketCeilings(
                withdrawalOrder, dynamicSequencingBracketRate, years, retirementYear, household.postStatus());
        return spliceDoubles(pre, post, idx);
    }

    /** Returns a copy of {@code pre} with {@code [fromIdx, len)} taken from {@code post}. */
    private static <T> T[] spliceObjects(T[] pre, T[] post, int fromIdx) {
        T[] out = pre.clone();
        System.arraycopy(post, fromIdx, out, fromIdx, out.length - fromIdx);
        return out;
    }

    /** Returns a copy of {@code pre} with {@code [fromIdx, len)} taken from {@code post}. */
    private static double[] spliceDoubles(double[] pre, double[] post, int fromIdx) {
        double[] out = pre.clone();
        System.arraycopy(post, fromIdx, out, fromIdx, out.length - fromIdx);
        return out;
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

    /**
     * Resolves the scenario's bond/cash-sleeve interest yield for the MC engine, falling back to
     * the same default the deterministic engine uses (see {@link
     * ScenarioParamsParser#DEFAULT_INTEREST_YIELD}) when the scenario's {@code params_json} doesn't
     * set one (audit C1).
     */
    private static double resolveInterestYield(GuardrailOptimizationInput input) {
        return input.interestYield() != null
                ? input.interestYield().doubleValue() : ScenarioParamsParser.DEFAULT_INTEREST_YIELD.doubleValue();
    }

    /**
     * Resolves the growth assumption fed to {@code ConversionSimulator} (audit C4 — frame mismatch).
     * Resolved HERE, once per run, so every consumer — {@link JointConversionSearch} (both the
     * DS-mode Phase-1 schedule and the joint-search arm build their optimizer from it) and
     * {@link GuardrailResponseBuilder} (response echo / persistence) — sees the identical value and
     * no consumer can accidentally re-convert it (double-Fisher).
     *
     * <p>{@code ConversionSimulator} grows its three pools at this rate every year (see {@code
     * traditional *= (1 + returnMean)}) while pricing conversion/RMD-target brackets in
     * CONSTANT-REAL terms ({@code computeMaxIncomeForBracket(..., ZERO)} — no inflation indexing
     * applied to the bracket ceilings). Those two numbers MUST live in the same frame: a real,
     * fee-adjusted rate. Feeding it a nominal rate (or a real rate that ignores fees) overstates
     * future traditional-balance growth relative to the flat bracket ceilings the simulator prices
     * against, which overstates projected RMD pressure and biases the optimizer toward
     * over-aggressive conversions.
     *
     * <p><strong>Default</strong> (the request's {@code returnMean} is absent — the normal case;
     * the frontend never sends {@code return_mean}): the scenario's own fee-adjusted,
     * allocation-blended REAL return — {@link PoolStrategy#blendedRealReturn}, the same
     * balance-weighted quantity the deterministic engine resolves as its default growth
     * assumption, computed here from this run's accounts and capital-market matrix so both
     * engines' notion of "the scenario's expected return" stay in lockstep.
     *
     * <p><strong>Explicit</strong> {@code returnMean} (wire contract: NOMINAL, the DTO's legacy
     * meaning): Fisher-converted to real via {@code (1+returnMean)/(1+inflation)-1}, then the
     * scenario fee is subtracted — mirroring {@link PoolStrategy#realReturnFor}'s override-account
     * handling, so an explicit override and the allocation-derived default land in the same frame.
     */
    static double resolveReturnMean(GuardrailOptimizationInput input, double inflationRate,
                                    double feeRate, RealReturnMatrix matrix) {
        if (input.returnMean() != null) {
            double nominal = input.returnMean().doubleValue();
            return (1 + nominal) / (1 + inflationRate) - 1 - feeRate;
        }
        var geoMeans = CapitalMarketAssumptionsProvider.geometricMeansOf(matrix);
        return PoolStrategy.blendedRealReturn(input.accounts(), geoMeans,
                BigDecimal.valueOf(inflationRate), BigDecimal.valueOf(feeRate)).doubleValue();
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
