package com.wealthview.projection;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.household.HouseholdContext;
import com.wealthview.core.projection.household.PersonId;
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

    /**
     * Task 15: the clock/floor clump that recurs across every quintet assembly site (main pre/post
     * phase, the stochastic joint phase, each survivor regime) -- always the SAME values within one
     * {@link #build} call, only ever read, never re-derived per regime. Bundling it removes the
     * parallel per-method parameter lists ({@code retirementYear, retirementAge, years,
     * retirementYearOffsetFromBase, essentialFloor, inflationRate, birthYear}) that previously had to
     * be threaded, in the same order, through every private signature below.
     */
    private record RunFrame(int retirementYear, int retirementAge, int years,
                            int retirementYearOffsetFromBase, double essentialFloor,
                            double inflationRate, int birthYear) {}

    /**
     * Task 15: the audit-verified quintet's output for ONE regime -- the income pipeline plus the
     * co-computed ordinary/LTCG tax tables, dynamic-sequencing bracket ceilings, and rental-income
     * delta (all year-indexed identically), assembled together by {@link #buildRegime}.
     */
    private record RegimeArrays(IncomePipeline pipeline, OrdinaryTaxTable[] ordinaryTables,
                                LtcgTaxTable[] ltcgTables, @Nullable double[] dsBracketCeilings,
                                double[] rentalIncomeByYear) {}

    OptimizationSetup build(GuardrailOptimizationInput input, RealReturnMatrix matrix) {
        int retirementYear = input.retirementDate().getYear();
        int retirementAge = retirementYear - input.birthYear();
        int endAge = input.endAge();
        int years = endAge - retirementAge;
        int rmdStartAge = RmdCalculator.rmdStartAge(input.birthYear());

        if (years <= 0) {
            return OptimizationSetup.emptyHorizon(retirementYear, retirementAge, endAge, years,
                    rmdStartAge);
        }

        int trialCount = input.trialCount();
        double initialPortfolio = totalPortfolio(input.accounts());
        double initTaxable = sumByType(input.accounts(), PoolType.TAXABLE);
        double initTraditional = sumByType(input.accounts(), PoolType.TRADITIONAL);
        double initRoth = sumByType(input.accounts(), PoolType.ROTH);
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

        // Sub-project B (stochastic mortality), task 5: when the toggle is on for a household with a
        // loaded table, precompute the per-trial sampled death ages and their derived transition/
        // truncate indices on a DEDICATED rng stream (seed + MORTALITY_SEED_OFFSET), kept fully
        // separate from the return-path rng above so it never perturbs the return draws. null ⇒
        // toggle off / single-person, the byte-identical anchor: the fixed-death HouseholdMcResolver
        // indices above drive every trial unchanged. Task 6 will consume these in the trial loop.
        MortalityDraws mortalityDraws = resolveMortalityDraws(input, retirementYear, years, trialCount);

        // Task 15: the clock/floor clump shared by every quintet assembly site below (main pre/post,
        // the stochastic joint phase, each survivor regime) -- built once, read everywhere.
        RunFrame frame = new RunFrame(retirementYear, retirementAge, years, retirementYearOffsetFromBase,
                essentialFloor, inflationRate, input.birthYear());

        // Both-alive income+tax quintet, spliced with the survivor phase from an in-window first death
        // (extracted so build() stays under the PMD NPath threshold -- the no-regression policy).
        RegimeArrays mainRegime = resolveMainRegime(input, household, filingStatus, frame, withdrawalOrder);
        IncomeYearData[] incomeData = mainRegime.pipeline().incomeData();
        double[] incomeByYear = mainRegime.pipeline().incomeByYear();
        double[] taxableIncomeByYear = mainRegime.pipeline().taxableIncomeByYear();
        double[] surplusTaxByYear = mainRegime.pipeline().surplusTaxByYear();
        double[] rentalAwareTaxableIncome = mainRegime.pipeline().rentalAwareTaxableIncome();
        // T18a-3: the per-year net rental income contribution, isolated as the delta between the
        // rental-aware base and the crude (non-rental-aware) base taxable income. rentalAwareTaxableIncome
        // floors its aggregate at zero (IncomeProjector#computeRentalAwareTaxable), so this delta can
        // under-count in the rare year where that floor actually clips (a documented approximation;
        // see TaxContext's javadoc) -- acceptable for the NIIT surtax's secondary role in the MC engine.
        double[] rentalIncomeByYear = mainRegime.rentalIncomeByYear();
        OrdinaryTaxTable[] ordinaryTaxTables = mainRegime.ordinaryTables();
        LtcgTaxTable[] ltcgTaxTables = mainRegime.ltcgTables();
        double[] dsBracketCeilingByYear = mainRegime.dsBracketCeilings();

        // Verify essential floor feasibility (constant real), then scale it by the survivor factor
        // from the transition year (household task 6) -- the single builder-side choke point every
        // downstream consumer (search, terminal/response pass, T16 adaptation corridor, T24 gate)
        // inherits. No-op for single-person / no in-window transition.
        double[] adjustedFloors = SustainabilitySearch.verifyEssentialFloor(
                portfolioPaths, incomeByYear, essentialFloor,
                confidenceLevel, years, trialCount);
        HouseholdMcResolver.scaleFromTransition(adjustedFloors, transitionIdx, household.survivorFactor(), years);

        TaxContext taxCtx = (initTraditional > 0 || initRoth > 0)
                ? new TaxContext(initTaxable, initTraditional, initRoth,
                        withdrawalOrder, ordinaryTaxTables, rentalAwareTaxableIncome, rentalIncomeByYear)
                : null;

        double portfolioFloor = input.portfolioFloor() != null
                ? input.portfolioFloor().doubleValue() : 0.0;

        // Capital-gains taxation inputs for the taxable pool's FIFO lots (Task 6):
        //  - initTaxableBasis seeds the initial lot's cost basis (embedded gain = balance - basis);
        //  - ltcgTaxTables (audit C5) are the per-year exact LTCG bracket tables, evaluated in the
        //    hot loop at the trial's ACTUAL ordinary stacking floor (base income + that year's
        //    conversion/draws/RMD excess) rather than a fixed probe rate;
        //  - dividendYield comes from the scenario's params_json (same field the deterministic engine
        //    reads via ScenarioParamsParser.dividendYield), falling back to the same default when unset.
        double initTaxableBasis = sumBasisByType(input.accounts(), PoolType.TAXABLE);
        double dividendYield = resolveDividendYield(input);
        double returnMean = resolveReturnMean(input, inflationRate, feeRate, matrix);
        // Audit C1: splits the MC taxable pool's yield the same way PoolStrategy.MultiPool does --
        // computed once from the taxable accounts' own allocation, falling back to 1.0 (100%
        // equity, ALL_US-equivalent -- byte-identical to pre-C1) when there are none.
        double interestYield = resolveInterestYield(input);
        double taxableEquityShare = PoolStrategy.taxableEquityShare(
                taxableAccounts(input.accounts())).doubleValue();

        // Sub-project B (stochastic mortality), task 6: when this run opted into stochastic mortality
        // (mortalityDraws != null ⇒ a household with a loaded table), additionally assemble the JOINT
        // (both-alive) arrays + both survivor regimes for the SEPARATE evaluation pass. This is purely
        // additive: the recommendation flow above is untouched, so toggle-off / single-person / fixed-
        // death runs are byte-identical (stochasticEval stays null there).
        StochasticEvalArrays stochasticEval = buildStochasticEvalArrays(input, mortalityDraws, frame,
                filingStatus, withdrawalOrder, portfolioPaths, confidenceLevel, trialCount);

        return new OptimizationSetup(
                new PortfolioSetup(initTaxable, initTraditional, initRoth,
                        initialPortfolio, withdrawalOrder, cashReserveYears, cashReturnRate,
                        terminalTarget, portfolioFloor, initTaxableBasis),
                new SimulationParameters(retirementYear, retirementAge, endAge, years,
                        trialCount, confidenceLevel, inflationRate, portfolioPaths,
                        returnPaths.taxableReturns(), returnPaths.traditionalReturns(),
                        returnPaths.rothReturns(), rmdStartAge, dividendYield, feeRate, returnMean,
                        interestYield, taxableEquityShare, household.survivorFactor(), household.sim(),
                        mortalityDraws, stochasticEval),
                new TaxIncomeContext(filingStatus, essentialFloor,
                        incomeByYear, taxableIncomeByYear, surplusTaxByYear,
                        incomeData, rentalAwareTaxableIncome, adjustedFloors, ordinaryTaxTables,
                        taxCtx, dsBracketCeilingByYear, ltcgTaxTables, rentalIncomeByYear));
    }

    private static List<ProjectionAccountInput> taxableAccounts(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .filter(a -> a.poolType() == PoolType.TAXABLE)
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
     *
     * <p>Household task 8 (T7 gap): {@code household} threads the same owner-age activation window
     * into {@link IncomeProjector#socialSecurityBenefitByYear} that {@code incomeData} was just built
     * with (see {@link #computeIncomePipeline}) — the two MUST agree on which years a Social Security
     * source is active, else {@code nonSsTaxable} below silently mis-attributes a spouse-owned
     * benefit's taxable dollars to "other" ordinary income instead of the two-tier SS formula.
     */
    private IncomeYearData[] applySocialSecurityTaxableShare(
            IncomeYearData[] incomeData, List<ProjectionIncomeSourceInput> sources,
            double essentialFloor, FilingStatus filingStatus, IncomeProjector.Context ctx) {
        double[] ssBenefitByYear = IncomeProjector.socialSecurityBenefitByYear(sources, ctx);
        BigDecimal inflationBd = BigDecimal.valueOf(ctx.scenarioInflationRate());
        IncomeYearData[] adjusted = new IncomeYearData[ctx.years()];
        for (int y = 0; y < ctx.years(); y++) {
            double ssBenefit = ssBenefitByYear[y];
            if (ssBenefit <= 0) {
                adjusted[y] = incomeData[y];
                continue;
            }
            double total = incomeData[y].totalIncome();
            double nonSsTaxable = Math.max(0, incomeData[y].taxableIncome() - ssBenefit);
            double expectedDraw = Math.max(0, essentialFloor - total);
            double provisionalOther = nonSsTaxable + expectedDraw;
            int yearsFromBase = Math.max(0, ctx.retirementYearOffsetFromBase() + y);
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
     * Task 15: resolves the main (fixed-death) flow's regime -- the both-alive pipeline (original
     * sources, configured filing status), spliced with the survivor phase (keep-larger SS +
     * survivor_percent sources, SINGLE filing status) from an in-window first death. Single-person /
     * no-in-window-transition runs return the both-alive regime unchanged (the byte-identical
     * anchor). Both phases share {@code household.context()} -- only {@code sources}/{@code status}
     * differ pre vs. post. Extracted from {@link #build} to keep its NPath complexity under the PMD
     * threshold.
     */
    private RegimeArrays resolveMainRegime(GuardrailOptimizationInput input,
            HouseholdMcResolver.Resolved household, FilingStatus filingStatus, RunFrame frame,
            String withdrawalOrder) {
        RegimeArrays pre = buildRegime(input.incomeSources(), filingStatus, household.context(), frame,
                withdrawalOrder, input.dynamicSequencingBracketRate());
        int transitionIdx = household.inWindowTransitionIdx();
        if (transitionIdx < 0) {
            return pre;
        }
        RegimeArrays post = buildRegime(household.survivorSources(), household.postStatus(),
                household.context(), frame, withdrawalOrder, input.dynamicSequencingBracketRate());
        return spliceRegime(pre, post, transitionIdx);
    }

    /**
     * Task 15: the shared factory for the audit-verified quintet {@code computeIncomePipeline} ->
     * {@link OrdinaryTaxTable#computeAll} -> {@link LtcgTaxTable#computeAll} ->
     * {@link #computeDsBracketCeilings} -> {@link #computeRentalIncomeDelta}. Every assembly site
     * (main pre/post phase, the stochastic joint phase, each survivor regime) calls this with the
     * SAME {@code frame} (the clock/floor clump never varies within one {@link #build} run) and the
     * SAME {@code withdrawalOrder}/{@code dsBracketRate} (also run-invariant, widened onto this
     * signature rather than approximated away -- {@link #computeDsBracketCeilings} needs them and no
     * site's value ever differs from another's); only {@code sources}, {@code status}, and
     * {@code household} vary per call.
     */
    private RegimeArrays buildRegime(List<ProjectionIncomeSourceInput> sources, FilingStatus status,
            @Nullable HouseholdContext household, RunFrame frame, String withdrawalOrder,
            @Nullable BigDecimal dsBracketRate) {
        var pipeline = computeIncomePipeline(sources, status, frame, household);
        OrdinaryTaxTable[] ordinary = OrdinaryTaxTable.computeAll(
                taxCalculator, frame.retirementYear(), frame.years(), status, frame.birthYear(), household);
        LtcgTaxTable[] ltcg = LtcgTaxTable.computeAll(capitalGainsTaxCalculator, taxCalculator,
                frame.retirementYear(), frame.years(), status, frame.inflationRate(), frame.birthYear(), household);
        double[] ds = computeDsBracketCeilings(
                withdrawalOrder, dsBracketRate, frame.years(), frame.retirementYear(), status);
        double[] rental = computeRentalIncomeDelta(
                pipeline.rentalAwareTaxableIncome(), pipeline.taxableIncomeByYear(), frame.years());
        return new RegimeArrays(pipeline, ordinary, ltcg, ds, rental);
    }

    /** Household task 6 + 8: the full per-year income pipeline (deterministic income → audit-B2
     * Social Security taxable share → income/taxable/surplus arrays → rental-aware taxable) for ONE
     * phase (a source list + filing status). Called once per regime by {@link #buildRegime}; {@link
     * #spliceRegime} joins a pre/post pair. {@code household} (task 8, T7 gap) threads the same
     * owner-age income-window resolution the deterministic engine's {@code IncomeSourceProcessor}
     * already applies into every {@link IncomeProjector} precompute call below, so a spouse-owned
     * age-gated source activates at the SPOUSE's age in the MC engine too — {@code null} for a
     * single-person run, byte-identical to the pre-task-8 uniform-age precompute. */
    private IncomePipeline computeIncomePipeline(List<ProjectionIncomeSourceInput> sources,
            FilingStatus status, RunFrame frame, @Nullable HouseholdContext household) {
        // ONE clock/household context for all three precomputes — their arrays are combined
        // below, so sharing the context is what keeps their activation windows in lockstep.
        var ctx = new IncomeProjector.Context(frame.retirementAge(), frame.years(),
                frame.retirementYearOffsetFromBase(), frame.inflationRate(), frame.birthYear(), household);
        IncomeYearData[] incomeData = IncomeProjector.computeDeterministic(sources, ctx);
        incomeData = applySocialSecurityTaxableShare(incomeData, sources, frame.essentialFloor(), status, ctx);
        var arrays = computeIncomeArrays(incomeData, frame.years(), frame.retirementYear(), status);
        double[] rentalAware = IncomeProjector.computeRentalAwareTaxable(
                arrays.taxableIncomeByYear(), sources, ctx);
        return new IncomePipeline(incomeData, arrays.incomeByYear(), arrays.taxableIncomeByYear(),
                arrays.surplusTaxByYear(), rentalAware);
    }

    /** The both-alive/survivor phase income arrays for one filing phase. */
    private record IncomePipeline(IncomeYearData[] incomeData, double[] incomeByYear,
            double[] taxableIncomeByYear, double[] surplusTaxByYear, double[] rentalAwareTaxableIncome) {}

    /**
     * Task 15: splices two regimes' worth of quintet output at {@code fromIdx}, field by field, via
     * {@link #spliceByTransition}. Generalizes the household-task-6 income splice to the whole
     * {@link RegimeArrays} bundle (income pipeline + ordinary/LTCG tables + DS ceilings + rental
     * delta) so main-flow assembly goes through the same factory as every other regime. {@code
     * dsBracketCeilings} null-ness is regime-invariant in practice (it depends only on run-constant
     * inputs — see {@link #computeDsBracketCeilings} — so {@code pre} and {@code post} always agree),
     * but both sides are still null-checked explicitly (not just {@code pre}) so the nullness is
     * verifiable locally rather than relying on that cross-call invariant.
     */
    private static RegimeArrays spliceRegime(RegimeArrays pre, RegimeArrays post, int fromIdx) {
        IncomePipeline prePipeline = pre.pipeline();
        IncomePipeline postPipeline = post.pipeline();
        IncomePipeline splicedPipeline = new IncomePipeline(
                spliceByTransition(prePipeline.incomeData(), postPipeline.incomeData(), fromIdx),
                spliceByTransition(prePipeline.incomeByYear(), postPipeline.incomeByYear(), fromIdx),
                spliceByTransition(prePipeline.taxableIncomeByYear(), postPipeline.taxableIncomeByYear(), fromIdx),
                spliceByTransition(prePipeline.surplusTaxByYear(), postPipeline.surplusTaxByYear(), fromIdx),
                spliceByTransition(
                        prePipeline.rentalAwareTaxableIncome(), postPipeline.rentalAwareTaxableIncome(), fromIdx));
        double[] preDs = pre.dsBracketCeilings();
        double[] postDs = post.dsBracketCeilings();
        double[] splicedDs = preDs == null || postDs == null ? null : spliceByTransition(preDs, postDs, fromIdx);
        return new RegimeArrays(splicedPipeline,
                spliceByTransition(pre.ordinaryTables(), post.ordinaryTables(), fromIdx),
                spliceByTransition(pre.ltcgTables(), post.ltcgTables(), fromIdx),
                splicedDs,
                spliceByTransition(pre.rentalIncomeByYear(), post.rentalIncomeByYear(), fromIdx));
    }

    /**
     * Sub-project B (stochastic mortality), task 6: assembles the JOINT (both-alive) income/tax arrays
     * plus BOTH survivor regimes for the separate stochastic evaluation pass. Only called when the run
     * opted into stochastic mortality (a household with a loaded table), so the fixed-death
     * recommendation flow above never reaches here and stays byte-identical.
     *
     * <p>The JOINT phase uses a context in which both spouses outlive the horizon, so every modeled
     * year is both-alive (MFJ per-person age-65, both income windows). {@code jointFloors} is the
     * essential floor UNSCALED — the per-trial splice ({@link TrialSimulator#simulateTrial}) re-applies
     * the survivor ×factor from each trial's own sampled first-death index. Each survivor regime is
     * built SINGLE with that survivor's own age (via a context in which the OTHER member died before the
     * window — {@link #survivorOnlyContext}) and that survivor's keep-larger-SS + survivor-% income.
     *
     * <p>Returns {@code null} when {@code mortalityDraws} is {@code null} (a non-stochastic run) — the
     * guard lives here rather than as a ternary in {@link #build} so build()'s NPath is unchanged.
     */
    @Nullable
    private StochasticEvalArrays buildStochasticEvalArrays(GuardrailOptimizationInput input,
            @Nullable MortalityDraws mortalityDraws, RunFrame frame, FilingStatus filingStatus,
            String withdrawalOrder, double[][] portfolioPaths, double confidenceLevel, int trialCount) {
        if (mortalityDraws == null) {
            return null;
        }
        int primaryBirthYear = frame.birthYear();
        // Non-null by the mortalityDraws gate in build() (stochastic mortality requires a spouse); the
        // requireNonNull makes that contract explicit for the unboxing here.
        int spouseBirthYear = Objects.requireNonNull(
                input.spouseBirthYear(), "stochastic mortality requires a spouse (household)");
        int horizonEndYear = primaryBirthYear + input.endAge();

        HouseholdContext jointCtx = bothAliveContext(primaryBirthYear, spouseBirthYear, horizonEndYear);
        RegimeArrays joint = buildRegime(input.incomeSources(), filingStatus, jointCtx, frame,
                withdrawalOrder, input.dynamicSequencingBracketRate());
        // Essential floor UNSCALED by the survivor factor -- the per-trial splice re-applies it.
        double[] jointFloors = SustainabilitySearch.verifyEssentialFloor(portfolioPaths,
                joint.pipeline().incomeByYear(), frame.essentialFloor(), confidenceLevel, frame.years(), trialCount);

        var regimes = new TrialSimulator.SurvivorRegime[2];
        regimes[TrialSimulator.PRIMARY_SURVIVES] = buildSurvivorRegime(input, PersonId.PRIMARY,
                spouseBirthYear, frame, withdrawalOrder, horizonEndYear);
        regimes[TrialSimulator.SPOUSE_SURVIVES] = buildSurvivorRegime(input, PersonId.SPOUSE,
                spouseBirthYear, frame, withdrawalOrder, horizonEndYear);

        return new StochasticEvalArrays(joint.pipeline().incomeByYear(), joint.pipeline().surplusTaxByYear(),
                jointFloors, joint.pipeline().rentalAwareTaxableIncome(), joint.ordinaryTables(),
                joint.ltcgTables(), joint.dsBracketCeilings(), joint.rentalIncomeByYear(), regimes);
    }

    /**
     * Sub-project B (stochastic mortality), task 6: one survivor regime, built SINGLE for the explicit
     * {@code survivor} identity — that survivor's keep-larger-SS + survivor-% income (via the
     * transition-year-invariant {@link SurvivorIncomeAdjuster#adjust(java.util.List, PersonId, int, int,
     * java.math.BigDecimal)}, computed once at {@code frame.retirementYear()}) and SINGLE-filer tax
     * tables at that survivor's own age (via {@link #survivorOnlyContext}, in which the OTHER member is
     * dead before the modeled window, so {@code filerAgeIn}/{@code age65QualifyingCount} return the
     * survivor's age for every year). {@code frame.birthYear()} stays the primary's — mirroring the
     * fixed-death survivor pipeline's own convention — because the context supplies the per-owner ages.
     */
    private TrialSimulator.SurvivorRegime buildSurvivorRegime(GuardrailOptimizationInput input,
            PersonId survivor, int spouseBirthYear, RunFrame frame, String withdrawalOrder,
            int horizonEndYear) {
        int primaryBirthYear = frame.birthYear();
        HouseholdContext ctx = survivorOnlyContext(
                survivor, primaryBirthYear, spouseBirthYear, frame.retirementYear(), horizonEndYear);
        var sources = SurvivorIncomeAdjuster.adjust(
                input.incomeSources(), survivor, frame.retirementYear(), input.baseYear(),
                BigDecimal.valueOf(frame.inflationRate()));
        RegimeArrays regime = buildRegime(sources, FilingStatus.SINGLE, ctx, frame, withdrawalOrder,
                input.dynamicSequencingBracketRate());
        return new TrialSimulator.SurvivorRegime(regime.pipeline().incomeByYear(),
                regime.pipeline().surplusTaxByYear(), regime.pipeline().rentalAwareTaxableIncome(),
                regime.ordinaryTables(), regime.ltcgTables(), regime.dsBracketCeilings(),
                regime.rentalIncomeByYear());
    }

    /** Task 6: a context in which BOTH spouses outlive the horizon, so every modeled year is both-alive
     * (joint MFJ per-person age-65 + both income windows) — the JOINT phase for the stochastic pass. */
    private static HouseholdContext bothAliveContext(int primaryBirthYear, int spouseBirthYear,
                                                     int horizonEndYear) {
        int beyond = horizonEndYear + 1;
        return HouseholdContext.of(primaryBirthYear, beyond - primaryBirthYear,
                spouseBirthYear, beyond - spouseBirthYear, horizonEndYear);
    }

    /** Task 6: a context in which the NON-survivor died at the retirement boundary (dead for every
     * modeled year) and the survivor outlives the horizon, so {@code filerAgeIn}/{@code
     * age65QualifyingCount} resolve to the survivor's own SINGLE-filer age throughout — used to build
     * each survivor regime's per-year tax tables/income at that survivor's age. */
    private static HouseholdContext survivorOnlyContext(PersonId survivor, int primaryBirthYear,
                                                        int spouseBirthYear, int retirementYear,
                                                        int horizonEndYear) {
        int beyond = horizonEndYear + 1;
        int primaryDeathAge = survivor == PersonId.PRIMARY
                ? beyond - primaryBirthYear : retirementYear - primaryBirthYear;
        int spouseDeathAge = survivor == PersonId.SPOUSE
                ? beyond - spouseBirthYear : retirementYear - spouseBirthYear;
        return HouseholdContext.of(primaryBirthYear, primaryDeathAge, spouseBirthYear, spouseDeathAge,
                horizonEndYear);
    }

    /** Returns a copy of {@code pre} with {@code [fromIdx, len)} taken from {@code post} -- the one
     * splice primitive shared by every pre/post transition (income pipeline, tax tables,
     * dynamic-sequencing ceilings, rental delta). Task 15: replaces the former {@code spliceObjects}. */
    private static <T> T[] spliceByTransition(T[] pre, T[] post, int fromIdx) {
        T[] out = pre.clone();
        System.arraycopy(post, fromIdx, out, fromIdx, out.length - fromIdx);
        return out;
    }

    /** The primitive-array overload of {@link #spliceByTransition(Object[], Object[], int)}. Task 15:
     * replaces the former {@code spliceDoubles}. */
    private static double[] spliceByTransition(double[] pre, double[] post, int fromIdx) {
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

    /**
     * Sub-project B (stochastic mortality), task 5: precomputes the per-trial {@link MortalityDraws}
     * when this run opts into stochastic mortality — {@code stochasticMortality} true AND a mortality
     * table is loaded AND the run is a household ({@code spouseBirthYear} present). Any of those
     * absent returns {@code null} (toggle off / single-person, the byte-identical anchor). The
     * mortality {@link Random} is a SEPARATE object from the return-path rng (see {@link #build}),
     * seeded {@code input.seed() + MortalityDrawGenerator.MORTALITY_SEED_OFFSET}; a {@code null} seed
     * yields an unseeded {@code new Random()}, mirroring the return-path rng's null-seed handling.
     */
    @Nullable
    private static MortalityDraws resolveMortalityDraws(GuardrailOptimizationInput input,
                                                        int retirementYear, int years, int trialCount) {
        if (!Boolean.TRUE.equals(input.stochasticMortality())
                || input.mortalityTable() == null
                || input.spouseBirthYear() == null) {
            return null;
        }
        Random mortRng = input.seed() != null
                ? new Random(input.seed() + MortalityDrawGenerator.MORTALITY_SEED_OFFSET)
                : new Random();
        return MortalityDrawGenerator.generate(input, retirementYear, years, mortRng, trialCount);
    }

    private static double sumByType(List<? extends ProjectionAccountInput> accounts, PoolType type) {
        return accounts.stream()
                .filter(a -> type == a.poolType())
                .mapToDouble(a -> a.initialBalance().doubleValue())
                .sum();
    }

    private static double sumBasisByType(List<? extends ProjectionAccountInput> accounts, PoolType type) {
        return accounts.stream()
                .filter(a -> type == a.poolType())
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
