package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.LotOwner;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CapitalGainsTaxCalculator;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Strategy for managing investment pool balances during projection year-loop. {@link MultiPool} is
 * the sole implementation: it manages traditional/roth/taxable sub-pools, with empty pools for any
 * account type absent from the scenario (audit C11 -- an all-taxable scenario is simply a MultiPool
 * with zero traditional and zero roth, not a distinct untaxed code path; see {@link #create}).
 */
sealed interface PoolStrategy permits PoolStrategy.MultiPool {

    static final int SCALE = 4;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    /** Withdrawal order string for the dynamic sequencing strategy. */
    static final String WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING = "dynamic_sequencing";

    BigDecimal getTotal();

    /** The traditional (pre-tax) pool balance; zero for strategies with no traditional/Roth split. */
    BigDecimal getTraditional();

    /**
     * Household task 4: the traditional (pre-tax) pool balance broken out per owner, so the engine
     * can drive one RMD stream per owner from each owner's own prior-year-end balance. Single-person
     * scenarios return a one-entry map keyed {@link PersonId#PRIMARY} (the byte-identical anchor:
     * {@link #getTraditional()} == this map's only value). The returned map is a detached copy —
     * mutating it does not affect the pool.
     */
    Map<PersonId, BigDecimal> getTraditionalByOwner();

    BigDecimal getWeightedReturn();

    BigDecimal applyContributions();

    /**
     * Grows every pool for the year. {@code retired} gates the taxable pool's yield-distribution
     * split (audit C8): only a RETIRED year books/taxes a qualified-dividend/ordinary-interest
     * distribution at all -- see {@code MultiPool#applyGrowth(boolean)}'s javadoc for the full
     * rationale. Traditional/Roth growth is unaffected by this parameter either way.
     */
    GrowthResult applyGrowth(boolean retired);

    /**
     * Task 14: parameter object for {@link #executeWithdrawals(WithdrawalCycleInputs)}, replacing the
     * former 6/8/9/10-arg telescoping overload chain (8 adjacent {@code BigDecimal} parameters was an
     * adjacent-same-type hazard at every call site). Component order/names are taken verbatim from the
     * former canonical 10-arg method's parameter list, so every positional overload below can build
     * one of these with a straight name-for-name mapping.
     *
     * <p>{@link #of(BigDecimal, int, int)} seeds the three fields every overload always supplied
     * ({@code need}/{@code year}/{@code age}) with the remaining seven defaulted to {@link
     * BigDecimal#ZERO} -- the same zero-fill the old telescoping overloads used one field at a time.
     * The {@code with*} methods let a caller override one field at a time from there, mirroring the
     * one-field-per-call shape of {@code TrialSimulator.SimulationConfig.Builder} without needing a
     * separate mutable builder class for a 10-component record.
     */
    record WithdrawalCycleInputs(
            BigDecimal need,
            int year,
            BigDecimal effectiveOtherIncome,
            BigDecimal conversionAmount,
            BigDecimal rmdAmount,
            int age,
            BigDecimal alreadyChargedBaseTax,
            BigDecimal extraPoolFundedTax,
            BigDecimal federallyTaxedSocialSecurity,
            BigDecimal netRentalIncome) {

        /** Seeds the always-supplied {@code need}/{@code year}/{@code age} trio; every other field
         * defaults to {@link BigDecimal#ZERO} (byte-identical to the old 6-arg overload's zero-fill). */
        // ShortMethodName: 'of' is the conventional JDK-style static factory method name (same
        // suppression as HouseholdContext.of).
        @SuppressWarnings("PMD.ShortMethodName")
        static WithdrawalCycleInputs of(BigDecimal need, int year, int age) {
            return new WithdrawalCycleInputs(need, year, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, age,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        WithdrawalCycleInputs withEffectiveOtherIncome(BigDecimal effectiveOtherIncome) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }

        WithdrawalCycleInputs withConversionAmount(BigDecimal conversionAmount) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }

        WithdrawalCycleInputs withRmdAmount(BigDecimal rmdAmount) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }

        WithdrawalCycleInputs withAlreadyChargedBaseTax(BigDecimal alreadyChargedBaseTax) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }

        WithdrawalCycleInputs withExtraPoolFundedTax(BigDecimal extraPoolFundedTax) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }

        WithdrawalCycleInputs withFederallyTaxedSocialSecurity(BigDecimal federallyTaxedSocialSecurity) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }

        WithdrawalCycleInputs withNetRentalIncome(BigDecimal netRentalIncome) {
            return new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                    alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, netRentalIncome);
        }
    }

    /**
     * Back-compat overload: no explicit signal that this year's base-income tax was already
     * charged elsewhere, and no additional tax obligation to fund from the pool cascade. Callers
     * that don't participate in {@link RetirementWithdrawalProcessor}'s surplus-tax bookkeeping
     * (audit A4) keep their exact pre-fix behavior via this overload -- see {@link
     * #executeWithdrawals(WithdrawalCycleInputs)}'s javadoc for the full contract.
     */
    default WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                                    BigDecimal conversionAmount, BigDecimal rmdAmount, int age) {
        return executeWithdrawals(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Back-compat overload predating the audit-C3 state-base adjustment: no federally-taxed Social
     * Security amount to exempt from the state base (zero -- behavior identical to pre-C3 for every
     * caller that doesn't thread it).
     */
    default WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                                    BigDecimal conversionAmount, BigDecimal rmdAmount, int age,
                                                    BigDecimal alreadyChargedBaseTax,
                                                    BigDecimal extraPoolFundedTax) {
        return executeWithdrawals(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                alreadyChargedBaseTax, extraPoolFundedTax, BigDecimal.ZERO);
    }

    /**
     * Back-compat overload predating the T18a-3 rental-income NIIT threading: no net rental income to
     * add to the LTCG/NIIT bundle's Net Investment Income base (zero -- behavior identical for every
     * caller that doesn't thread it). See {@link #executeWithdrawals(WithdrawalCycleInputs)}'s javadoc
     * for the full contract.
     */
    default WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount, BigDecimal rmdAmount, int age,
                                           BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                                           BigDecimal federallyTaxedSocialSecurity) {
        return executeWithdrawals(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, BigDecimal.ZERO);
    }

    /**
     * Positional back-compat overload carrying the full argument list -- thin delegate to {@link
     * #executeWithdrawals(WithdrawalCycleInputs)}, the single abstract method every implementation
     * now provides. See that method's javadoc for the full contract (RMD-forcing order, the audit-C2
     * traditional gross-up, the audit-C3 state-base adjustments, and the T18a-3 NIIT rental threading).
     */
    default WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount, BigDecimal rmdAmount, int age,
                                           BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                                           BigDecimal federallyTaxedSocialSecurity, BigDecimal netRentalIncome) {
        return executeWithdrawals(new WithdrawalCycleInputs(need, year, effectiveOtherIncome, conversionAmount,
                rmdAmount, age, alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity,
                netRentalIncome));
    }

    /**
     * Executes the year's withdrawal/RMD/tax cascade.
     *
     * <p>MultiPool grosses up any traditional slice of the resulting tax bill (audit C2): once the
     * taxable pool can no longer fund the bill, the traditional distribution that pays it is ITSELF
     * ordinary income, so it must fund its own tax too. That extra draw is debited from traditional
     * like any other distribution, folded into the returned ordinary-income reporting (so the
     * Social Security provisional-income convergence and the tax-liability/DTO breakdown all agree),
     * and counts toward RMD satisfaction. See {@code PoolStrategy.MultiPool#growTraditionalGrossUp}
     * for the fixed-point search.
     *
     * <p>{@code inputs.}{@link WithdrawalCycleInputs#netRentalIncome() netRentalIncome} (T18a-3) is
     * additionally threaded into the year's LTCG/NIIT bundle's Net Investment Income base -- see
     * {@code CapitalGainsTaxCalculator#computeLtcgTax}'s rental overload for the mechanics. Zero when
     * no rental income sources are active.
     *
     * @param inputs the withdrawal cycle's inputs; see the individual accessors below for the
     *     contract of each field.
     *     <ul>
     *     <li>{@link WithdrawalCycleInputs#rmdAmount() rmdAmount} (T18a-1) the amount of this year's
     *     RMD ALREADY forced out of traditional by a prior {@link #forceRmd} call — this method
     *     itself no longer forces anything out of the pool for RMD purposes. It is folded straight
     *     into the year's traditional-sourced ordinary income for tax attribution, exactly like the
     *     spend draw's own traditional portion. Zero when no RMD applies this year.
     *     <li>{@link WithdrawalCycleInputs#alreadyChargedBaseTax() alreadyChargedBaseTax} the dollar
     *     amount of ordinary tax on {@code (effectiveOtherIncome + conversionAmount)} that {@link
     *     RetirementWithdrawalProcessor}'s spending-plan surplus branch has ALREADY charged this year
     *     (whether funded from surplus cash or left for {@code extraPoolFundedTax} below) -- an
     *     EXPLICIT signal that replaces the old {@code totalNeed <= 0} ("noSpendDraw") proxy (audit
     *     A4 / T2 review). The old proxy assumed {@code totalNeed<=0 ⟺ the surplus branch charged
     *     tax}, which holds for {@code TierBasedSpendingPlan} (its resolved {@code
     *     portfolioWithdrawal} is derived from the SAME {@code spendingNeed - activeIncome}
     *     quantities that decide the surplus branch) but NOT for {@code GuardrailSpendingInput},
     *     whose {@code resolveYear} returns a pre-computed {@code portfolioWithdrawal} that ignores
     *     live income entirely -- so its sign can disagree with whether the surplus branch actually
     *     ran. Zero when no such charge exists (deficit years, no spending plan, or the
     *     Guardrail-mismatch case): the caller has NOT pre-charged anything, so the bundle below must
     *     charge it in full. IMPORTANT (audit C3): when non-zero, the caller must have computed it
     *     with the SAME state-base adjustments this method applies (same {@code
     *     federallyTaxedSocialSecurity}, zero LTCG income) so the marginal subtraction below nets
     *     exactly and stays monotone.
     *     <li>{@link WithdrawalCycleInputs#extraPoolFundedTax() extraPoolFundedTax} additional tax
     *     obligation (the surplus branch's tax remainder once the year's surplus was insufficient to
     *     cover it, and/or self-employment tax, which has no bundle of its own anywhere else this
     *     year) that must be funded from the SAME pool cascade as the withdrawal-tax bundle, on top
     *     of it -- audit A4's "route the unfunded remainder through deductFromPools" fix. Zero when
     *     nothing is outstanding.
     *     <li>{@link WithdrawalCycleInputs#federallyTaxedSocialSecurity() federallyTaxedSocialSecurity}
     *     the year's converged federally-taxable Social Security amount (audit B2's fixed point),
     *     threaded into the ordinary-tax bundle's state-base computation so states that exempt Social
     *     Security don't tax it (audit C3). The realized LTCG/dividend state addition needs no
     *     parameter -- it is computed internally from the FIFO sale and the year's booked dividend.
     *     Zero when no Social Security is taxable.
     *     </ul>
     */
    WithdrawalTaxResult executeWithdrawals(WithdrawalCycleInputs inputs);

    /** Back-compat overload predating the audit-C3 state-base adjustment (zero taxable SS). */
    default ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome,
                                                    BigDecimal rmdAmount) {
        return executeRothConversion(year, effectiveOtherIncome, rmdAmount, BigDecimal.ZERO);
    }

    /**
     * Executes the year's Roth conversion. {@code federallyTaxedSocialSecurity} is the year's
     * converged federally-taxable Social Security amount, threaded into the conversion-tax bundle's
     * state-base computation (audit C3) exactly as in {@link #executeWithdrawals}; the conversion
     * bundle adds NO LTCG income to the state base -- the year's realized LTCG/dividends belong to
     * the withdrawal bundle, which taxes them once.
     */
    ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome, BigDecimal rmdAmount,
                                           BigDecimal federallyTaxedSocialSecurity);

    /**
     * Everything the engine knows about a projection year when it asks the
     * strategy to assemble the year DTO. Pool balances are the strategy's own
     * state and deliberately absent — each implementation appends what it has.
     */
    record YearDtoContext(int year, int age, BigDecimal startBalance,
                          BigDecimal contributions, BigDecimal totalGrowth,
                          BigDecimal withdrawals, boolean retired,
                          BigDecimal conversionAmount, BigDecimal taxLiability,
                          GrowthResult growthResult,
                          BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                          BigDecimal withdrawalFromRoth,
                          TaxSourceResult combinedTaxSource,
                          BigDecimal rmdAmount, BigDecimal ltcgTax,
                          BigDecimal earlyWithdrawalPenalty) {}

    /** Back-compat overload predating the audit-C3 state-base adjustment (zero taxable SS). */
    default ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                            BigDecimal overrideAmount, BigDecimal rmdAmount) {
        return executeRothConversionOverride(year, effectiveOtherIncome, overrideAmount, rmdAmount,
                BigDecimal.ZERO);
    }

    default ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                            BigDecimal overrideAmount, BigDecimal rmdAmount,
                                                            BigDecimal federallyTaxedSocialSecurity) {
        return executeRothConversion(year, effectiveOtherIncome, rmdAmount, federallyTaxedSocialSecurity);
    }

    /**
     * T18a-1: IRS ordering — the year's RMD must be distributed BEFORE any Roth conversion.
     * Physically forces {@code min(rmdAmount, traditional)} out of the traditional pool into a
     * fresh at-cost taxable lot (a real, unconditional distribution — the retiree "has the cash"
     * once it happens), independent of whatever a later spend-draw withdrawal decides to do.
     * Callers thread the RETURNED (possibly capped) amount into {@link #executeWithdrawals}'s
     * {@code rmdAmount} parameter, which after this call represents the amount ALREADY forced —
     * see that method's javadoc. A {@code null} or non-positive {@code rmdAmount} is a no-op
     * returning zero.
     */
    BigDecimal forceRmd(BigDecimal rmdAmount);

    /**
     * Household task 4: force this year's RMD out of ONE owner's traditional pool (into a fresh
     * at-cost taxable lot), capped at that owner's balance. Identical mechanics to
     * {@link #forceRmd(BigDecimal)} but scoped to a single owner's pool — the engine calls it once
     * per owner whose age has reached that owner's own SECURE-2.0 RMD start age, so an age-gap couple
     * runs two independent streams (the correctness this feature exists for). A {@code null} or
     * non-positive amount, or an owner absent from the traditional pool, is a no-op returning zero.
     */
    BigDecimal forceRmd(PersonId owner, BigDecimal rmdAmount);

    /**
     * Household task 5: applies the first-death transition to the pools, atomically and exactly once,
     * at the household's transition-year boundary. Three effects, all conservation-preserving in
     * total portfolio VALUE:
     * <ol>
     *   <li><b>Spousal rollover</b> — the deceased owner's traditional and Roth balances transfer in
     *       full to the survivor (treat-as-own); the deceased's slices become zero. No tax, no lot
     *       creation. Total is unchanged (pinned).</li>
     *   <li><b>Basis step-up</b> — the taxable pool's lots step up EXACTLY per owner (HP1, see
     *       {@link TaxableLotsBd#stepUpByOwner}): each lot steps by its OWN owner's statutory rate —
     *       joint-held lots by {@code communityProperty ? 1.0 : 0.5} of their embedded gain, the
     *       decedent's own lots fully (1.0), the survivor's own lots not at all (0.0) — and
     *       decedent-owned lots then retag to the survivor. Because every lot carries its source
     *       account's owner tag, mixed-ownership taxable steps up exactly rather than by the earlier
     *       initial-balance-weighted blended factor applied uniformly. Value (and the balance) is
     *       unchanged.</li>
     *   <li><b>Filing flip</b> — the pool's filing status becomes {@link FilingStatus#SINGLE} for
     *       this and every later year; all downstream tax bundles (ordinary, LTCG/NIIT, Roth
     *       conversion, Social Security tiers via {@link #getFilingStatus()}, IRMAA) pick it up
     *       through the existing per-year construction — no calculator is special-cased.</li>
     * </ol>
     * Never called for a single-person scenario (the engine guards on the household transition year),
     * so it can never move a pre-household path.
     */
    void applyFirstDeathTransition(PersonId deceased, PersonId survivor, boolean communityProperty);

    void floorAtZero();

    /**
     * Deposits a surplus amount into the taxable account.
     */
    void depositToTaxable(BigDecimal amount);

    ProjectionYearDto buildYearDto(YearDtoContext ctx);

    /**
     * Returns the MAGI value to pass to processIncomeSources.
     */
    BigDecimal getMagi();

    /**
     * Returns the filing status to use for tax computations and income-source processing.
     */
    FilingStatus getFilingStatus();

    /**
     * Whether income sources should be processed every year (true) or only when retired (false).
     */
    boolean processIncomeSourcesEveryYear();

    /**
     * Returns the accumulated tax breakdown from the most recent withdrawal + conversion cycle.
     * Present only for MultiPool when a CombinedTaxCalculator is in use.
     */
    default Optional<CombinedTaxResult> getLastTaxBreakdown() {
        return Optional.empty();
    }

    /**
     * Whether SE tax should be added to tax liability.
     */
    boolean tracksSETax();

    /**
     * Computes the effective other income (for Roth conversion and withdrawal tax context).
     */
    BigDecimal computeEffectiveOtherIncome(BigDecimal activeIncome, BigDecimal incomeSourceCash);

    /** Log tag for the projection type. */
    String logTag();

    record GrowthResult(BigDecimal total, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {}

    record TaxSourceResult(BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth) {
        static final TaxSourceResult ZERO = new TaxSourceResult(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        TaxSourceResult add(TaxSourceResult other) {
            return new TaxSourceResult(
                    fromTaxable.add(other.fromTaxable),
                    fromTraditional.add(other.fromTraditional),
                    fromRoth.add(other.fromRoth));
        }
    }

    record ConversionResult(BigDecimal amountConverted, BigDecimal taxLiability, TaxSourceResult taxSource) {}

    /**
     * {@code totalWithdrawn} (T18a-5a) is the year's aggregate GROSS distribution across the three
     * pools, INCLUDING any forced RMD excess (previously excluded here while {@code fromTraditional}
     * counted it, breaking the {@code fromTaxable + fromTraditional + fromRoth == totalWithdrawn}
     * sum identity whenever an RMD forced money out beyond the spend draw). Like
     * {@code fromTraditional}/{@code traditionalOrdinaryIncome}, it reports the RMD as a
     * distribution the moment it is force-taken, regardless of whether the retiree needed the cash
     * (the after-tax remainder may be reinvested to taxable rather than spent) -- "withdrawal" here
     * means "left the tax-advantaged pool", the same convention {@code withdrawal_from_traditional}
     * already uses, not literally "spent by the household". The sum identity still does NOT hold in
     * a year with an audit-C2 tax-funding gross-up (which further inflates {@code fromTraditional}
     * without a matching addition here) -- closing that residual gap is a separate, narrower fix
     * scope than this item.
     *
     * <p>{@code ltcgTax} is the long-term capital-gains portion of {@code taxLiability} (zero when no
     * {@code CapitalGainsTaxCalculator} is wired -- see {@link PoolConfig}). It is broken out
     * separately so the engine can fold it into the year's federal-tax breakdown -- see
     * {@link RetirementTaxAnnotator}.
     *
     * <p>{@code realizedLtcgIncome} is the year's realized long-term capital-gains + qualified-
     * dividend INCOME (not tax), floored at zero. The Social Security provisional-income convergence
     * (audit B2) folds it into AGI-ex-SS alongside {@code fromTraditional} and the Roth conversion.
     *
     * <p>{@code earlyWithdrawalPenalty} (T18a-4) is the 10% IRC 72(t) additional tax on this year's
     * traditional-sourced DISTRIBUTIONS (spend draw + RMD force-out + the audit-C2 tax-funding
     * gross-up slice -- everything {@code fromTraditional} above counts) when {@code age} is below
     * {@link RetirementAges#EARLY_WITHDRAWAL_AGE}. It is ALREADY folded into {@code taxLiability}
     * (additive) and funded from the pools; broken out here so the engine can surface it as its own
     * DTO field. Roth conversions are OUT OF SCOPE -- converted dollars move internally to Roth,
     * they are not withdrawn to the household.
     *
     * <p>{@code ordinaryInterestIncome} (audit C1) is the year's ordinary-interest income (the
     * taxable pool's bond+cash sleeve), floored at zero. Unlike {@code realizedLtcgIncome} it is
     * NOT LTCG income -- it is already folded into the ORDINARY {@code taxableIncome} bundle
     * {@code taxLiability} was computed from (see {@code executeWithdrawals}); broken out here so
     * callers can fold it into the Social Security provisional-income convergence
     * ({@code realizedPortfolioTaxable}) and the displayed tax-breakdown recompute
     * ({@link RetirementTaxAnnotator}) the same way {@code fromTraditional}/{@code conversionAmount}
     * already are.
     */
    record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability,
                               BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth,
                               TaxSourceResult taxSource, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome,
                               BigDecimal earlyWithdrawalPenalty, BigDecimal ordinaryInterestIncome) {}

    /**
     * Opaque, per-implementation snapshot of a pool's mutable state, taken AFTER the year's growth
     * and BEFORE income/conversion/withdrawal, so the deterministic engine's Social Security
     * provisional-income fixed-point loop (audit B2) can re-run those steps from an identical
     * starting state each iteration.
     */
    sealed interface Memento permits MultiPool.MultiPoolMemento {}

    /** Captures the pool's mutable state for later {@link #restore(Memento)}. */
    Memento snapshot();

    /** Restores mutable state captured by a prior {@link #snapshot()} from THIS pool instance. */
    void restore(Memento memento);

    // --- PoolConfig + Factory Method ---

    /** Configuration record encapsulating multi-pool construction parameters. */
    record PoolConfig(
            FilingStatus filingStatus,
            BigDecimal otherIncome,
            BigDecimal annualRothConversion,
            String rothConversionStrategy,
            BigDecimal targetBracketRate,
            Integer rothConversionStartYear,
            WithdrawalOrder withdrawalOrder,
            TaxCalculationStrategy taxCalculator,
            BigDecimal dynamicSequencingBracketRate,
            Map<AssetClass, Double> geoMeans,
            BigDecimal inflationRate,
            CapitalGainsTaxCalculator capitalGainsTaxCalculator,
            BigDecimal dividendYield,
            BigDecimal interestYield,
            BigDecimal feeRate,
            int baseYear,
            FederalTaxCalculator federalTaxCalculator) {

        /**
         * Back-compat constructor for callers that predate allocation-driven returns: uses an
         * empty capital-market map and zero inflation, so returns come solely from per-account
         * overrides (the legacy {@code expectedReturn} path). No capital-gains calculator and a
         * zero dividend yield, so the taxable pool tracks cost basis but realizes no LTCG tax and
         * no dividend drag — the pre-lots scalar behavior, bit-for-bit. Zero fee rate for the same
         * reason (audit B1) — these legacy callers stay fee-free. Zero interest yield for the same
         * reason (audit C1) — these legacy callers realize no bond-sleeve interest either.
         */
        PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                   String rothConversionStrategy, BigDecimal targetBracketRate,
                   Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                   TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
            this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                    rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                    Map.of(), BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
        }

        /**
         * Back-compat constructor for allocation-driven callers that predate capital-gains taxation:
         * supplies capital-market means and inflation but no LTCG calculator / dividend yield /
         * interest yield / fee rate / federal standard-deduction source.
         */
        PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                   String rothConversionStrategy, BigDecimal targetBracketRate,
                   Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                   TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate,
                   Map<AssetClass, Double> geoMeans, BigDecimal inflationRate) {
            this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                    rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                    geoMeans, inflationRate, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
        }

        /**
         * Entry point for building a config fluently, mirroring {@code
         * TrialSimulator.SimulationConfig.Builder}'s pattern. The nine arguments are the
         * tax/withdrawal-strategy core every pool needs (the same nine the smallest back-compat
         * constructor above takes); the allocation-driven and capital-gains-taxation knobs default to
         * the documented byte-identical no-op values the back-compat constructors already encode
         * (empty capital-market map, zero inflation/dividend/interest/fee, no LTCG or federal-deduction
         * calculator) so a builder chain only names the mechanisms a caller actually uses.
         */
        static Builder builder(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                               String rothConversionStrategy, BigDecimal targetBracketRate,
                               Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                               TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
            return new Builder(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy,
                    targetBracketRate, rothConversionStartYear, withdrawalOrder, taxCalculator,
                    dynamicSequencingBracketRate);
        }

        /** Fluent builder. Defaults are the same no-op anchors the back-compat constructors above
         * document: empty capital-market map, zero inflation, no LTCG calculator, zero dividend/
         * interest/fee, base year 0, no federal-deduction source. */
        static final class Builder {
            private final FilingStatus filingStatus;
            private final BigDecimal otherIncome;
            private final BigDecimal annualRothConversion;
            private final String rothConversionStrategy;
            private final BigDecimal targetBracketRate;
            private final Integer rothConversionStartYear;
            private final WithdrawalOrder withdrawalOrder;
            private final TaxCalculationStrategy taxCalculator;
            private final BigDecimal dynamicSequencingBracketRate;
            private Map<AssetClass, Double> geoMeans = Map.of();
            private BigDecimal inflationRate = BigDecimal.ZERO;
            private CapitalGainsTaxCalculator capitalGainsTaxCalculator;
            private BigDecimal dividendYield = BigDecimal.ZERO;
            private BigDecimal interestYield = BigDecimal.ZERO;
            private BigDecimal feeRate = BigDecimal.ZERO;
            private int baseYear;
            private FederalTaxCalculator federalTaxCalculator;

            private Builder(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                            String rothConversionStrategy, BigDecimal targetBracketRate,
                            Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                            TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
                this.filingStatus = filingStatus;
                this.otherIncome = otherIncome;
                this.annualRothConversion = annualRothConversion;
                this.rothConversionStrategy = rothConversionStrategy;
                this.targetBracketRate = targetBracketRate;
                this.rothConversionStartYear = rothConversionStartYear;
                this.withdrawalOrder = withdrawalOrder;
                this.taxCalculator = taxCalculator;
                this.dynamicSequencingBracketRate = dynamicSequencingBracketRate;
            }

            Builder capitalMarketAssumptions(Map<AssetClass, Double> geoMeans, BigDecimal inflationRate) {
                this.geoMeans = geoMeans;
                this.inflationRate = inflationRate;
                return this;
            }

            Builder capitalGainsTaxCalculator(CapitalGainsTaxCalculator capitalGainsTaxCalculator) {
                this.capitalGainsTaxCalculator = capitalGainsTaxCalculator;
                return this;
            }

            Builder dividendYield(BigDecimal dividendYield) {
                this.dividendYield = dividendYield;
                return this;
            }

            Builder interestYield(BigDecimal interestYield) {
                this.interestYield = interestYield;
                return this;
            }

            Builder feeRate(BigDecimal feeRate) {
                this.feeRate = feeRate;
                return this;
            }

            Builder baseYear(int baseYear) {
                this.baseYear = baseYear;
                return this;
            }

            Builder federalTaxCalculator(FederalTaxCalculator federalTaxCalculator) {
                this.federalTaxCalculator = federalTaxCalculator;
                return this;
            }

            PoolConfig build() {
                return new PoolConfig(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy,
                        targetBracketRate, rothConversionStartYear, withdrawalOrder, taxCalculator,
                        dynamicSequencingBracketRate, geoMeans, inflationRate, capitalGainsTaxCalculator,
                        dividendYield, interestYield, feeRate, baseYear, federalTaxCalculator);
            }
        }
    }

    /**
     * Computes the real (inflation-adjusted), fee-adjusted return for a single account. When a
     * nominal expected-return override is present it is converted to real via
     * {@code (1+nominal)/(1+inflation) - 1}; otherwise the account's allocation is blended against
     * the supplied capital-market geometric means (already real). The scenario's annual all-in
     * fee/expense-ratio drag (audit B1) is then subtracted uniformly — a fee is a fee, whether the
     * account's return is allocation-derived or a fixed override — so both paths converge through
     * this single choke point before returning.
     */
    static BigDecimal realReturnFor(ProjectionAccountInput acct,
                                    Map<AssetClass, Double> geoMeans, BigDecimal inflationRate,
                                    BigDecimal feeRate) {
        BigDecimal grossReal;
        if (acct.expectedReturnOverride().isPresent()) {
            BigDecimal nominal = acct.expectedReturnOverride().get();
            grossReal = BigDecimal.ONE.add(nominal)
                    .divide(BigDecimal.ONE.add(inflationRate), SCALE + 4, ROUNDING)
                    .subtract(BigDecimal.ONE);
        } else {
            double blended = 0.0;
            for (var e : acct.allocation().weights().entrySet()) {
                Double g = geoMeans.get(e.getKey());
                if (g != null) {
                    blended += e.getValue().doubleValue() * g;
                }
            }
            grossReal = BigDecimal.valueOf(blended).setScale(SCALE + 4, ROUNDING);
        }
        return grossReal.subtract(feeRate);
    }

    /**
     * Audit C1: the balance-weighted "equity share" (us_stock + intl_stock weight) of a set of
     * taxable accounts, used to split the taxable pool's annual yield between qualified-dividend
     * treatment (the equity share, at {@code dividendYield}) and ordinary-interest treatment (the
     * remaining bond+cash share, at {@code interestYield} — {@link AssetClass#CASH} is treated as
     * bond-like ordinary interest at the same proxy rate; a distinct money-market yield is not
     * modeled). Computed ONCE from each account's INITIAL balance/allocation, exactly like {@link
     * #realReturnFor}'s per-account return — it does not track allocation drift as the pool's
     * composition or relative balances change over the projection horizon.
     *
     * <p>Returns {@link BigDecimal#ONE} (100% equity, 0% bond/cash) when the accounts carry no
     * balance at all — the split is then moot (there is no taxable value to distribute a yield
     * against), and defaulting to the ALL_US-equivalent share keeps this degenerate case on the
     * same code path as the byte-identical backward-compat anchor.
     */
    static BigDecimal taxableEquityShare(List<ProjectionAccountInput> taxableAccounts) {
        BigDecimal totalBalance = sumInitialBalances(taxableAccounts);
        if (totalBalance.signum() <= 0) {
            return BigDecimal.ONE;
        }
        BigDecimal weighted = BigDecimal.ZERO;
        for (var acct : taxableAccounts) {
            BigDecimal weights0 = acct.allocation().weights().getOrDefault(AssetClass.US_STOCK, BigDecimal.ZERO);
            BigDecimal equityWeight = weights0.add(
                    acct.allocation().weights().getOrDefault(AssetClass.INTL_STOCK, BigDecimal.ZERO));
            weighted = weighted.add(acct.initialBalance().multiply(equityWeight));
        }
        return weighted.divide(totalBalance, SCALE + 4, ROUNDING);
    }

    /**
     * The balance-weighted real, fee-adjusted return across the WHOLE portfolio (every account,
     * every pool type) — the same aggregate {@link #create} resolves as the deterministic engine's
     * default growth assumption via {@link #computeWeightedReturn}. Reused by the Roth-conversion
     * optimizer's guardrail-flow return resolution (audit C4) so the two engines' notion of "the
     * scenario's expected real return" stays in lockstep instead of drifting apart.
     */
    static BigDecimal blendedRealReturn(List<ProjectionAccountInput> accounts,
                                        Map<AssetClass, Double> geoMeans, BigDecimal inflationRate,
                                        BigDecimal feeRate) {
        BigDecimal totalBalance = sumInitialBalances(accounts);
        return computeWeightedReturn(accounts, totalBalance, geoMeans, inflationRate, feeRate);
    }

    /**
     * Factory method that builds a {@link MultiPool} from the account types present, grouping
     * accounts by type and encapsulating all construction details. Always builds a MultiPool --
     * audit C11: an all-taxable (or all-any-single-type, or empty) account list used to dispatch to
     * a separate {@code SinglePool} strategy that computed NO tax of any kind (not even on outside
     * income), which silently understated every all-taxable scenario. MultiPool already supports
     * empty sub-pools (an absent account type just groups to an empty list below), so routing every
     * scenario through it is a behavior-preserving generalization for mixed portfolios and a
     * real-taxation fix for the all-taxable ones.
     */
    static PoolStrategy create(List<ProjectionAccountInput> accounts, PoolConfig config) {
        Map<AssetClass, Double> geoMeans = config.geoMeans();
        BigDecimal inflationRate = config.inflationRate();
        BigDecimal feeRate = config.feeRate();
        Map<PoolType, List<ProjectionAccountInput>> grouped = accounts.stream()
                .collect(Collectors.groupingBy(ProjectionAccountInput::poolType,
                        () -> new EnumMap<>(PoolType.class), Collectors.toList()));

        BigDecimal totalBalance = sumInitialBalances(grouped.getOrDefault(PoolType.TAXABLE, List.of()))
                .add(sumInitialBalances(grouped.getOrDefault(PoolType.TRADITIONAL, List.of())))
                .add(sumInitialBalances(grouped.getOrDefault(PoolType.ROTH, List.of())));

        return new MultiPool(grouped,
                poolWeightedReturn(grouped.getOrDefault(PoolType.TAXABLE, List.of()), geoMeans, inflationRate,
                        feeRate),
                poolWeightedReturn(grouped.getOrDefault(PoolType.TRADITIONAL, List.of()), geoMeans, inflationRate,
                        feeRate),
                poolWeightedReturn(grouped.getOrDefault(PoolType.ROTH, List.of()), geoMeans, inflationRate,
                        feeRate),
                computeWeightedReturn(accounts, totalBalance, geoMeans, inflationRate, feeRate),
                config);
    }

    private static BigDecimal sumInitialBalances(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .map(ProjectionAccountInput::initialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** HP1: annual taxable contribution summed over the accounts owned by one {@link LotOwner}
     * category, so each year's contribution enters the commingled lots tagged by its owner. Lives
     * on the interface (like {@link #sumInitialBalances}) to keep {@link MultiPool}'s own
     * complexity down. */
    private static BigDecimal sumContribsByOwner(List<ProjectionAccountInput> accounts, LotOwner owner) {
        return accounts.stream()
                .filter(account -> account.ownerType() == owner)
                .map(ProjectionAccountInput::annualContribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The balance-weighted real return across the given accounts. Each account's real return is
     * resolved via {@link #realReturnFor}; the result is the aggregate the pool grows at (or, for
     * a single account-type group, that pool's own return).
     */
    private static BigDecimal computeWeightedReturn(List<ProjectionAccountInput> accounts,
                                                     BigDecimal totalBalance,
                                                     Map<AssetClass, Double> geoMeans,
                                                     BigDecimal inflationRate,
                                                     BigDecimal feeRate) {
        if (totalBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (var account : accounts) {
            weightedSum = weightedSum.add(
                    account.initialBalance().multiply(realReturnFor(account, geoMeans, inflationRate, feeRate)));
        }
        return weightedSum.divide(totalBalance, SCALE + 4, ROUNDING);
    }

    /** Balance-weighted real return for a single account-type pool. */
    private static BigDecimal poolWeightedReturn(List<ProjectionAccountInput> accounts,
                                                  Map<AssetClass, Double> geoMeans,
                                                  BigDecimal inflationRate,
                                                  BigDecimal feeRate) {
        return computeWeightedReturn(accounts, sumInitialBalances(accounts), geoMeans, inflationRate, feeRate);
    }

    // --- MultiPool ---

    final class MultiPool implements PoolStrategy {
        /**
         * The taxable pool, tracked as FIFO cost-basis lots so withdrawals can realize long-term
         * capital gains. Replaces the pre-lots scalar {@code taxable} balance; its {@code totalValue()}
         * is the taxable balance everywhere the scalar was read.
         */
        private final TaxableLotsBd lots;
        /**
         * Maximum polish passes for the audit-C2 traditional-tax gross-up AFTER the closed-form
         * warm start (see {@link #growTraditionalGrossUp}). Raised 5 -> 10 (T10 review): plain
         * cold-start iteration contracts only at the marginal rate m per pass, leaving multi-dollar
         * residuals at m >= 0.22 within 5 passes; with the warm start the loop usually breaks on
         * pass 1, so the higher cap costs nothing in practice (nested B2 x C2 worst case is
         * 4 x ~12 bracket computations per projection year -- trivial).
         */
        private static final int MAX_GROSS_UP_ITERATIONS = 10;
        /**
         * Convergence tolerance (dollars) for the audit-C2 gross-up fixed point -- the same $1
         * tolerance {@code YearFinanceResolver} uses for its Social Security fixed point.
         */
        private static final BigDecimal GROSS_UP_TOLERANCE = BigDecimal.ONE;
        /**
         * Defensive cap on the chord marginal rate used for the warm-start jump in
         * {@link #growTraditionalGrossUp}: a real combined federal+state marginal rate cannot reach
         * 100% (which would make the {@code implied/(1-m)} closed form diverge); 50% comfortably
         * covers the worst realistic combination (37% federal + 13.3% CA). The cap only shortens
         * the JUMP -- the polish loop still converges to the true fixed point afterwards.
         */
        private static final BigDecimal GROSS_UP_WARM_START_RATE_CAP = new BigDecimal("0.50");
        /** IRC 72(t) -- 10% additional tax on early (pre-59½) traditional-account distributions,
         * a statutory constant (T18a-4). */
        private static final BigDecimal EARLY_WITHDRAWAL_PENALTY_RATE = new BigDecimal("0.10");
        /**
         * Household task 4: the traditional and Roth pools are now owner-keyed ({@link OwnerPool} over
         * {@link PersonId}) so an age-gap couple can run one RMD stream per owner and contributions
         * can route to the owning spouse's pool. Every public accessor and the tax cascade still
         * operate on the SUM across owners ({@link OwnerPool#total()}); a single-person scenario has
         * exactly one entry ({@link PersonId#PRIMARY}) so all arithmetic reduces to the pre-household
         * scalar path bit-for-bit. Within-type draws (spend draw, tax cascade) split proportionally by
         * owner balance — see {@link OwnerPool#debitProportional}. The joint taxable pool stays a
         * single {@link TaxableLotsBd} (joint accounts are taxable-only).
         */
        private final OwnerPool traditional;
        private final OwnerPool roth;
        private Optional<CombinedTaxResult> lastTaxBreakdown = Optional.empty();
        /**
         * The current year's qualified-dividend income (value × dividend yield), booked in
         * {@link #applyGrowth(boolean)} and consumed as LTCG income in {@link #executeWithdrawals}.
         * Reset each {@code applyGrowth} call. Audit C8: a RETIRED year is the only year that ever
         * books this at all -- see {@link #applyGrowth(boolean)}'s javadoc for why an accumulation
         * year no longer runs the distribution split (previously it booked a dividend every year but
         * only ever taxed/consumed it in a retired or RMD-forced year, which is exactly the untaxed
         * basis-step bug that item fixes).
         */
        private BigDecimal qualifiedDividendIncome = BigDecimal.ZERO;
        /**
         * Audit C1: the current year's ordinary-interest income (bond+cash share of the taxable
         * pool's yield, at {@link #interestYield}), booked in {@link #applyGrowth(boolean)} alongside
         * {@link #qualifiedDividendIncome} and consumed as ORDINARY income (not LTCG) in {@link
         * #executeWithdrawals} — it joins {@code taxableIncome} directly, the same bundle {@code
         * effectiveOtherIncome}/{@code conversionAmount}/the traditional distribution feed. Reset
         * each {@code applyGrowth} call; audit C8: zero for every accumulation year, exactly like
         * {@link #qualifiedDividendIncome} above.
         */
        private BigDecimal ordinaryInterestIncome = BigDecimal.ZERO;

        private final OwnerPool tradContrib;
        private final OwnerPool rothContrib;
        /**
         * HP1: annual taxable contributions split by the contributing account's owner category
         * ({@code joint}/{@code primary}/{@code spouse}), so each year's contribution enters the
         * commingled lot set tagged with its owner (the first-death step-up then applies that owner's
         * exact statutory rate). Single-person scenarios put the whole contribution on
         * {@link #taxableContribPrimary} (accounts default to {@code primary}), so the tagged lot has
         * the same value as the pre-HP1 single aggregate contribution lot — byte-identical.
         */
        private final BigDecimal taxableContribJoint;
        private final BigDecimal taxableContribPrimary;
        private final BigDecimal taxableContribSpouse;
        private final BigDecimal taxableReturn;
        private final BigDecimal traditionalReturn;
        private final BigDecimal rothReturn;
        private final BigDecimal weightedReturn;

        /**
         * Household task 5: no longer final — the first-death transition flips it to
         * {@link FilingStatus#SINGLE} from the transition year (see {@link #applyFirstDeathTransition}).
         * Deliberately NOT part of {@link MultiPoolMemento}: the Social Security convergence loop
         * restores pool balances but must leave the post-transition filing status in place, so the
         * flip is applied once and never re-applied or reverted across the fixed-point re-runs.
         */
        private FilingStatus filingStatus;
        private final BigDecimal otherIncome;
        private final BigDecimal annualRothConversion;
        private final String rothConversionStrategy;
        private final BigDecimal targetBracketRate;
        private final Integer rothConversionStartYear;
        private final WithdrawalOrder withdrawalOrder;
        private final TaxCalculationStrategy taxCalculator;
        private final BigDecimal dynamicSequencingBracketRate;

        // Capital-gains taxation collaborators (null calculator ⇒ taxable pool stays untaxed,
        // preserving pre-lots behavior for callers that don't wire capital gains).
        private final CapitalGainsTaxCalculator capitalGainsTaxCalculator;
        private final BigDecimal dividendYield;
        /** Audit C1: nominal annual coupon proxy for the taxable pool's bond+cash sleeve. */
        private final BigDecimal interestYield;
        /**
         * Audit C1: the taxable pool's balance-weighted equity share (us_stock + intl_stock),
         * computed once at construction via {@link PoolStrategy#taxableEquityShare} — {@code 1.0}
         * (bond share {@code 0.0}) for every ALL_US or no-allocation account, the byte-identical
         * backward-compat anchor for {@link #applyGrowth(boolean)}'s yield split.
         */
        private final BigDecimal taxableEquityShare;
        private final BigDecimal inflationRate;
        private final int baseYear;
        /**
         * Source of the federal standard deduction, used ONLY to net the LTCG stacking floor down
         * to the same base the ordinary tax computed on (see {@link #resolveOrdinaryDeduction}). Null
         * for callers that don't wire it, in which case the floor stays gross (pre-fix behavior).
         */
        private final FederalTaxCalculator federalTaxCalculator;

        /**
         * Legacy uniform-return constructor: all three pools grow at the same {@code weightedReturn}.
         * Retained so existing direct-construction callers (and tests) keep their behavior.
         */
        MultiPool(Map<PoolType, List<ProjectionAccountInput>> grouped,
                  BigDecimal weightedReturn,
                  PoolConfig config) {
            this(grouped, weightedReturn, weightedReturn, weightedReturn, weightedReturn, config);
        }

        MultiPool(Map<PoolType, List<ProjectionAccountInput>> grouped,
                  BigDecimal taxableReturn, BigDecimal traditionalReturn, BigDecimal rothReturn,
                  BigDecimal weightedReturn,
                  PoolConfig config) {
            // Seed one FIFO lot per taxable account: basis = its cost basis, value = its balance,
            // so any embedded (unrealized) gain is carried into the projection. HP1: each lot is
            // tagged with its source account's owner so the first-death step-up applies the EXACT
            // per-owner statutory rate (no more single blended factor over the commingled pool).
            this.lots = new TaxableLotsBd();
            var taxableAccounts = grouped.getOrDefault(PoolType.TAXABLE, List.of());
            for (var acct : taxableAccounts) {
                lots.addLot(acct.costBasis(), acct.initialBalance(), acct.ownerType());
            }
            // Audit C1: the taxable pool's own allocation-derived equity share, computed once from
            // the same account list the lots above were seeded from.
            this.taxableEquityShare = taxableEquityShare(taxableAccounts);
            this.traditional = OwnerPool.ofBalances(grouped.getOrDefault(PoolType.TRADITIONAL, List.of()));
            this.roth = OwnerPool.ofBalances(grouped.getOrDefault(PoolType.ROTH, List.of()));

            this.tradContrib = OwnerPool.ofContributions(grouped.getOrDefault(PoolType.TRADITIONAL, List.of()));
            this.rothContrib = OwnerPool.ofContributions(grouped.getOrDefault(PoolType.ROTH, List.of()));
            // HP1: taxable contributions split by owner so each year's contribution lot is tagged.
            this.taxableContribJoint = sumContribsByOwner(taxableAccounts, LotOwner.JOINT);
            this.taxableContribPrimary = sumContribsByOwner(taxableAccounts, LotOwner.PRIMARY);
            this.taxableContribSpouse = sumContribsByOwner(taxableAccounts, LotOwner.SPOUSE);

            this.taxableReturn = taxableReturn;
            this.traditionalReturn = traditionalReturn;
            this.rothReturn = rothReturn;
            this.weightedReturn = weightedReturn;
            this.filingStatus = config.filingStatus();
            this.otherIncome = config.otherIncome();
            this.annualRothConversion = config.annualRothConversion();
            this.rothConversionStrategy = config.rothConversionStrategy();
            this.targetBracketRate = config.targetBracketRate();
            this.rothConversionStartYear = config.rothConversionStartYear();
            this.withdrawalOrder = config.withdrawalOrder();
            this.taxCalculator = config.taxCalculator();
            this.dynamicSequencingBracketRate = config.dynamicSequencingBracketRate();
            this.capitalGainsTaxCalculator = config.capitalGainsTaxCalculator();
            this.dividendYield = config.dividendYield();
            this.interestYield = config.interestYield();
            this.inflationRate = config.inflationRate();
            this.baseYear = config.baseYear();
            this.federalTaxCalculator = config.federalTaxCalculator();
        }

        @Override
        public BigDecimal getTotal() {
            return lots.totalValue().add(traditional.total()).add(roth.total());
        }

        @Override
        public BigDecimal getTraditional() {
            return traditional.total();
        }

        @Override
        public Map<PersonId, BigDecimal> getTraditionalByOwner() {
            return traditional.byOwner();
        }

        /**
         * Number of open taxable lots -- a package-visible test-observability hook (audit C8) so a
         * unit test can confirm an accumulation-year {@link #applyGrowth(boolean)} call creates NO
         * new at-cost lot, without reaching into {@link #lots} via reflection.
         */
        int taxableLotCount() {
            return lots.lotCount();
        }

        @Override
        public BigDecimal getWeightedReturn() {
            return weightedReturn;
        }

        @Override
        public BigDecimal applyContributions() {
            // Household task 4: contributions route to the owner configured on each account (the
            // tradContrib/rothContrib schedules are keyed the same way traditional/roth are), so a
            // spouse-owned account grows the spouse's pool only.
            traditional.creditAll(tradContrib);
            roth.creditAll(rothContrib);
            // HP1: new contributions enter at cost (basis = value), each tagged by the contributing
            // account's owner. A zero amount is a no-op (addLot ignores it), so a single-person
            // scenario adds exactly one primary-tagged lot with the same value as the pre-HP1
            // aggregate contribution lot — byte-identical.
            lots.addLot(taxableContribJoint, taxableContribJoint, LotOwner.JOINT);
            lots.addLot(taxableContribPrimary, taxableContribPrimary, LotOwner.PRIMARY);
            lots.addLot(taxableContribSpouse, taxableContribSpouse, LotOwner.SPOUSE);
            BigDecimal taxableContrib = taxableContribJoint.add(taxableContribPrimary).add(taxableContribSpouse);
            return tradContrib.total().add(rothContrib.total()).add(taxableContrib);
        }

        @Override
        public GrowthResult applyGrowth(boolean retired) {
            BigDecimal tradGrowth = traditional.grow(traditionalReturn);
            BigDecimal rothGrowth = roth.grow(rothReturn);

            BigDecimal taxableBefore = lots.totalValue();
            BigDecimal taxableGrowth = taxableBefore.multiply(taxableReturn).setScale(SCALE, ROUNDING);
            BigDecimal targetTotal = taxableBefore.add(taxableGrowth);

            if (retired) {
                applyTaxableYieldSplit(targetTotal);
            } else {
                // Audit C8: an ACCUMULATION year books NO distribution at all -- lots appreciate at
                // the FULL total return r (pure, unrealized appreciation; basis untouched, no new
                // at-cost lot created). Pre-C8 the split below ran every year regardless of
                // retirement status, so an accumulation-year dividend/interest distribution was
                // reinvested as a fresh at-cost lot even though it is NEVER taxed pre-retirement (see
                // qualifiedDividendIncome/ordinaryInterestIncome's javadocs for the one narrow
                // exception this closes too: a still-working, RMD-forced year, whose taxable-pool
                // yield WAS taxed pre-C8 -- gating strictly on `retired` intentionally stops taxing
                // it, since that RMD-forced tax was itself only a side effect of the same
                // now-removed accumulation-year split, not a separate deliberate design point) -- a
                // real, untaxed basis step-up a taxed investor would never get for free. Treating the
                // whole return as unrealized appreciation instead simply defers the entire gain to
                // the eventual sale, which is what an untaxed reinvestment economically IS in this
                // model's constant-real frame. growToExactTotal keeps the reported total return
                // (taxableGrowth/targetTotal, and therefore the whole projection's BALANCE
                // trajectory) byte-identical to the pre-C8 split path's own total-preserving
                // guarantee -- only the lots' internal basis composition differs.
                lots.growToExactTotal(taxableReturn, targetTotal);
                qualifiedDividendIncome = BigDecimal.ZERO;
                ordinaryInterestIncome = BigDecimal.ZERO;
            }

            // OwnerPool.grow (above) already credited each owner's traditional/Roth slice with its
            // rounded growth; tradGrowth/rothGrowth are the summed totals for the GrowthResult.
            return new GrowthResult(tradGrowth.add(rothGrowth).add(taxableGrowth),
                    taxableGrowth, tradGrowth, rothGrowth);
        }

        /**
         * Split the taxable return: existing lots appreciate at (r − blendedYield); the
         * distribution (≈ value × blendedYield) is reinvested as a fresh at-cost lot, booked as
         * the residual to the exact target total, so the taxable pool still grows at precisely
         * r regardless of the split (bit-identical to the pre-lots scalar path when both yields
         * are 0). Audit C1: the distribution itself is then split BETWEEN qualified-dividend
         * income (the equity share, taxableEquityShare, at dividendYield — TAXED as LTCG/
         * qualified-dividend income, unchanged from pre-C1) and ordinary-interest income (the
         * remaining bond+cash share, at interestYield — TAXED as ORDINARY income instead). Audit
         * C8: only ever invoked for a RETIRED year (see {@link #applyGrowth(boolean)}) -- an
         * accumulation year takes a separate, split-free path instead.
         *
         * <p>bondShare == 0 (every ALL_US / no-allocation account, taxableEquityShare == 1) takes a
         * SEPARATE, byte-identical-to-pre-C1 code path rather than the general proportional
         * split below — interestYield is then completely irrelevant, and this avoids any
         * BigDecimal scale/precision drift the general division-based split could otherwise
         * introduce for the single most common account shape (the backward-compat anchor).
         */
        private void applyTaxableYieldSplit(BigDecimal targetTotal) {
            BigDecimal bondShare = BigDecimal.ONE.subtract(taxableEquityShare);

            if (bondShare.signum() == 0) {
                lots.grow(taxableReturn.subtract(dividendYield));
                BigDecimal dividend = targetTotal.subtract(lots.totalValue());
                lots.addLot(dividend);
                qualifiedDividendIncome = dividend.max(BigDecimal.ZERO);
                ordinaryInterestIncome = BigDecimal.ZERO;
            } else {
                BigDecimal equityYieldRate = taxableEquityShare.multiply(dividendYield);
                BigDecimal blendedYield = equityYieldRate.add(bondShare.multiply(interestYield));
                lots.grow(taxableReturn.subtract(blendedYield));
                BigDecimal totalDistribution = targetTotal.subtract(lots.totalValue());
                lots.addLot(totalDistribution);

                if (blendedYield.signum() != 0) {
                    BigDecimal dividendPortion = totalDistribution.multiply(equityYieldRate)
                            .divide(blendedYield, SCALE + 4, ROUNDING);
                    qualifiedDividendIncome = dividendPortion.max(BigDecimal.ZERO);
                    ordinaryInterestIncome = totalDistribution.subtract(dividendPortion).max(BigDecimal.ZERO);
                } else {
                    qualifiedDividendIncome = BigDecimal.ZERO;
                    ordinaryInterestIncome = BigDecimal.ZERO;
                }
            }
        }

        @Override
        public WithdrawalTaxResult executeWithdrawals(WithdrawalCycleInputs inputs) {
            BigDecimal totalNeed = inputs.need();
            int year = inputs.year();
            BigDecimal effectiveOtherIncome = inputs.effectiveOtherIncome();
            BigDecimal conversionAmount = inputs.conversionAmount();
            BigDecimal rmdAmount = inputs.rmdAmount();
            int age = inputs.age();
            BigDecimal alreadyChargedBaseTax = inputs.alreadyChargedBaseTax();
            BigDecimal extraPoolFundedTax = inputs.extraPoolFundedTax();
            BigDecimal federallyTaxedSocialSecurity = inputs.federallyTaxedSocialSecurity();
            BigDecimal netRentalIncome = inputs.netRentalIncome();

            // A fully income-covered year (totalNeed <= 0, e.g. pension/rental/SS covers spending)
            // still owes the RMD force-out and this year's dividend/LTCG tax below -- required
            // distributions and portfolio income are due regardless of whether the retiree needed
            // the cash (audit A2). Only the pool allocation for the spending draw itself is skipped
            // here; RMD forcing and taxation always run past this guard.
            boolean noSpendDraw = totalNeed.compareTo(BigDecimal.ZERO) <= 0;

            BigDecimal fromTaxable = BigDecimal.ZERO;
            BigDecimal fromTraditional = BigDecimal.ZERO;
            BigDecimal fromRoth = BigDecimal.ZERO;

            if (!noSpendDraw) {
                var withdrawalContext = new WithdrawalOrderStrategy.WithdrawalContext(
                        effectiveOtherIncome, conversionAmount, rmdAmount, age, year);
                WithdrawalOrderStrategy strategy = WithdrawalOrderStrategy.forOrder(
                        withdrawalOrder, dynamicSequencingBracketRate, taxCalculator, filingStatus,
                        withdrawalContext);

                WithdrawalOrderStrategy.Result allocation =
                        strategy.execute(totalNeed, lots.totalValue(), traditional.total(), roth.total());
                if (allocation != null) {
                    fromTaxable = allocation.fromTaxable();
                    fromTraditional = allocation.fromTraditional();
                    fromRoth = allocation.fromRoth();
                }
            }

            // Selling the taxable draw FIFO realizes a long-term capital gain (oldest lots first).
            // The traditional/Roth spend draws split proportionally by owner balance (task 4).
            BigDecimal realizedGain = lots.sellFifo(fromTaxable);
            traditional.debitProportional(fromTraditional);
            roth.debitProportional(fromRoth);

            // T18a-1: the RMD was already forced out of traditional (into a fresh taxable lot) by
            // a prior forceRmd() call -- BEFORE this method ran, and before any Roth conversion
            // (IRS ordering). rmdAmount here is that already-forced figure; fold it straight into
            // this year's traditional-sourced ordinary income alongside the spend draw's own
            // traditional portion -- no further pool mutation for RMD purposes happens here.
            BigDecimal rmdForced = rmdAmount != null ? rmdAmount.max(BigDecimal.ZERO) : BigDecimal.ZERO;
            BigDecimal traditionalOrdinaryIncome = fromTraditional.add(rmdForced);

            // Realized LTCG + qualified-dividend INCOME, floored at zero (a net realized loss's
            // small AGI offset is out of scope for this model). Computed BEFORE the ordinary-tax
            // bundle because it feeds two places: the state base of that bundle for states that tax
            // capital gains as ordinary income (audit C3), and AGI-ex-SS for the Social Security
            // provisional-income convergence (audit B2).
            BigDecimal realizedLtcgIncome = realizedGain.add(qualifiedDividendIncome).max(BigDecimal.ZERO);

            // Audit C1: this year's ordinary-interest income (the taxable pool's bond+cash sleeve,
            // booked in applyGrowth) joins the ORDINARY bundle directly -- unlike qualifiedDividend/
            // realizedGain, it is NOT LTCG income, so it does NOT flow through realizedLtcgIncome
            // above (no LTCG-rate treatment, no LTCG-bracket floor stacking). It DOES flow through
            // the same ordinary path effectiveOtherIncome/conversionAmount/traditionalOrdinaryIncome
            // already take: the full taxableIncome bundle below (which feeds the Social Security
            // provisional-income convergence via realizedPortfolioTaxable -- see
            // YearFinanceResolver -- and the state base for capital-gains-as-ordinary states).
            BigDecimal taxableIncome = traditionalOrdinaryIncome.add(effectiveOtherIncome).add(conversionAmount)
                    .add(ordinaryInterestIncome);
            var ordinaryTax = computeOrdinaryTax(taxableIncome, year, effectiveOtherIncome,
                    conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity);
            CombinedTaxResult detailed = ordinaryTax.detailed();

            // Long-term capital-gains tax on the realized FIFO gain + this year's qualified dividend,
            // stacked on ordinary income against the 0/15/20 LTCG brackets (+ deflated NIIT, T18a-3:
            // now including net rental income in the NIIT base). This runs whenever executeWithdrawals
            // runs -- both in retirement and, since T18a-2, in a still-working RMD-age year. LTCG is a
            // federal tax, so it belongs in the federal-tax breakdown -- it folds into taxLiability and
            // drains the pools via the same cascade as the ordinary withdrawal tax, AND is returned
            // separately (below) so the engine can fold it into the year's federalTax field. It is
            // deliberately NOT added to lastTaxBreakdown here: for retired years RetirementTaxAnnotator
            // recomputes (and overwrites) the DTO's federal/state breakdown from scratch downstream of
            // this call, so that is where the fold actually has to happen -- see
            // RetirementTaxAnnotator#annotate.
            BigDecimal ltcgTax = computeLtcgTax(realizedGain, taxableIncome, year, detailed, netRentalIncome);

            // C2: a tax payment sourced from the traditional pool is ITSELF an ordinary-income
            // distribution once withdrawn -- converge the traditional-funded slice of the bill to
            // a fixed point (see growTraditionalGrossUp) BEFORE physically draining the pools, so
            // the extra draw is debited, taxed, and reported as ordinary income all in one pass.
            // taxableAvail/traditionalAvail are frozen here (post spend-draw, post RMD force-out,
            // pre-tax-cascade) -- the fixed-point search below never mutates the pools.
            // NOTE: growTraditionalGrossUp's internal recomputes call computeOrdinaryTax, which
            // ALREADY updates lastTaxBreakdown as a side effect (with its own conditional-recording
            // logic -- see that method's javadoc) on every call it makes, including the LAST one when
            // the loop actually iterates. Nothing further needs to happen here: when the loop doesn't
            // iterate (no traditional slice to gross up), lastTaxBreakdown is untouched by this method
            // and stays exactly what the pre-loop computeOrdinaryTax call above already left it as --
            // byte-identical to pre-C2 behavior.
            var grossUp = growTraditionalGrossUp(taxableIncome, year, effectiveOtherIncome, conversionAmount,
                    alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity, ltcgTax,
                    extraPoolFundedTax, ordinaryTax, lots.totalValue(), traditional.total());
            BigDecimal totalWithdrawalTax = grossUp.tax();
            // The converged gross-up draw is itself a traditional distribution: fold it into the
            // reported ordinary income so it (a) feeds the audit-B2 Social Security provisional-
            // income loop the same way the spend draw/RMD excess already do (see
            // YearFinanceResolver#realizedPortfolioTaxable, fed by this field) and (b) makes
            // RetirementTaxAnnotator's independent federal/state recompute agree with taxLiability.
            traditionalOrdinaryIncome = traditionalOrdinaryIncome.add(grossUp.traditionalGrossUp());

            TaxSourceResult withdrawalTaxSource = totalWithdrawalTax.compareTo(BigDecimal.ZERO) > 0
                    ? deductFromPools(totalWithdrawalTax) : TaxSourceResult.ZERO;

            // T18a-4: 10% IRC 72(t) additional tax on traditional DISTRIBUTIONS before age 59½
            // (whole-year proxy: age < RetirementAges.EARLY_WITHDRAWAL_AGE, the SAME proxy the
            // withdrawal-order strategies already use to steer clear of early traditional draws).
            // Applies to the year's FULL traditional-sourced distribution captured by
            // traditionalOrdinaryIncome (spend draw + RMD force-out + the C2 tax-funding gross-up
            // slice) -- Roth conversions are OUT OF SCOPE (the converted dollars move internally to
            // Roth, not withdrawn to the household). Funded via its own simple pool drain, NOT
            // re-run through growTraditionalGrossUp's fixed point -- re-stacking the penalty's own
            // funding draw as further taxable/penalizable income is a documented, out-of-scope
            // second-order effect, the same category as ltcgTax/extraPoolFundedTax above.
            BigDecimal earlyWithdrawalPenalty = age < RetirementAges.EARLY_WITHDRAWAL_AGE
                    ? traditionalOrdinaryIncome.multiply(EARLY_WITHDRAWAL_PENALTY_RATE)
                    : BigDecimal.ZERO;
            if (earlyWithdrawalPenalty.compareTo(BigDecimal.ZERO) > 0) {
                withdrawalTaxSource = withdrawalTaxSource.add(deductFromPools(earlyWithdrawalPenalty));
            }

            // T18a-5a: the aggregate includes the forced RMD excess (rmdForced) on top of the raw
            // spend-draw allocation, so it reconciles exactly with fromTaxable + traditionalOrdinaryIncome
            // (which already counts rmdForced) + fromRoth -- see the WithdrawalTaxResult javadoc.
            return new WithdrawalTaxResult(
                    fromTaxable.add(fromTraditional).add(fromRoth).add(rmdForced),
                    totalWithdrawalTax.add(earlyWithdrawalPenalty),
                    fromTaxable, traditionalOrdinaryIncome, fromRoth, withdrawalTaxSource, ltcgTax,
                    realizedLtcgIncome, earlyWithdrawalPenalty, ordinaryInterestIncome);
        }

        /** The year's ordinary-income tax bundle: the net pool-funded tax plus the detailed result. */
        private record OrdinaryTax(BigDecimal tax, @Nullable CombinedTaxResult detailed) {
            static final OrdinaryTax NONE = new OrdinaryTax(BigDecimal.ZERO, null);
        }

        /**
         * Computes the withdrawal cycle's ordinary-income tax bundle and records it in
         * {@code lastTaxBreakdown}. The state base of this bundle adds the year's realized
         * LTCG/dividend income (for states taxing capital gains as ordinary income) and exempts the
         * federally-taxed Social Security amount (for states that exempt it) -- audit C3; the
         * DISPLAYED breakdown ({@code RetirementTaxAnnotator}) recomputes with the SAME inputs, so
         * display and pool funding agree.
         *
         * <p>The {@code realizedLtcgIncome > 0} guard leg covers a retiree with ZERO ordinary income
         * but realized gains/dividends: a state that taxes capital gains as ordinary income still
         * owes state tax on them. For every other strategy that extra computation is all-zero and
         * {@code lastTaxBreakdown} stays empty (see the recording condition below), preserving
         * pre-C3 behavior byte-for-byte.
         *
         * <p>Tax already charged elsewhere this year is netted out to avoid double-counting through
         * separate progressive-bracket calculations:
         * <ul>
         *   <li>{@code conversionAmount > 0}: its tax was already computed and paid by
         *       {@code executeConversionWithAmount} (called earlier in the year) -- recompute the
         *       base tax fresh (bracket-accurate marginal subtraction) since that call's own paid
         *       amount isn't threaded through here. The recompute MUST use the same state-base
         *       adjustments the conversion bundle used (same SS exemption, no LTCG income -- the
         *       LTCG state addition belongs to THIS bundle only) so the subtraction nets exactly
         *       and full-bundle &ge; base-bundle stays monotone (audit C3).</li>
         *   <li>{@code alreadyChargedBaseTax > 0}: {@code RetirementWithdrawalProcessor}'s surplus
         *       branch already charged exactly this dollar amount against
         *       {@code (effectiveOtherIncome + conversionAmount)}, whether funded from the year's
         *       cash surplus or left in {@code extraPoolFundedTax} -- subtract the EXPLICIT figure
         *       directly (audit A4 / T2 review: replaces the old {@code totalNeed<=0} "noSpendDraw"
         *       proxy, which mis-fires for {@code GuardrailSpendingInput} -- see the interface
         *       javadoc). This bundle then contributes only the marginal tax caused by the forced
         *       RMD/spend draw on top of that base.</li>
         *   <li>Otherwise (positive spend draw, no conversion, nothing pre-charged) this is the
         *       year's first and only ordinary-tax computation, so the full bundle stands as-is.</li>
         * </ul>
         */
        private OrdinaryTax computeOrdinaryTax(BigDecimal taxableIncome, int year,
                                                BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                                                BigDecimal alreadyChargedBaseTax, BigDecimal realizedLtcgIncome,
                                                BigDecimal federallyTaxedSocialSecurity) {
            if (taxCalculator == null
                    || (taxableIncome.compareTo(BigDecimal.ZERO) <= 0
                        && realizedLtcgIncome.compareTo(BigDecimal.ZERO) <= 0)) {
                return OrdinaryTax.NONE;
            }

            var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus,
                    realizedLtcgIncome, federallyTaxedSocialSecurity);

            BigDecimal tax;
            if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
                var baseTax = taxCalculator.computeDetailedTax(
                        conversionAmount.add(effectiveOtherIncome), year, filingStatus,
                        BigDecimal.ZERO, federallyTaxedSocialSecurity);
                tax = detailed.totalTax().subtract(baseTax.totalTax()).max(BigDecimal.ZERO);
            } else if (alreadyChargedBaseTax.compareTo(BigDecimal.ZERO) > 0) {
                tax = detailed.totalTax().subtract(alreadyChargedBaseTax).max(BigDecimal.ZERO);
            } else {
                tax = detailed.totalTax();
            }

            // Record whenever ordinary income exists (pre-C3 behavior), or when the new
            // zero-ordinary-income leg actually produced state tax; an all-zero result on that new
            // leg leaves the breakdown empty exactly as before.
            if (taxableIncome.compareTo(BigDecimal.ZERO) > 0
                    || detailed.totalTax().compareTo(BigDecimal.ZERO) > 0) {
                lastTaxBreakdown = Optional.of(detailed);
            }
            return new OrdinaryTax(tax, detailed);
        }

        /**
         * The converged audit-C2 gross-up: how much EXTRA traditional income the fixed point below
         * settled on, and the resulting total bill. {@code lastTaxBreakdown} is NOT threaded through
         * here -- {@code computeOrdinaryTax} already updates it as a side effect on every call it
         * makes (including the search's last one), with its own conditional-recording logic, so the
         * field is left exactly as that method's last call set it.
         */
        private record GrossUpResult(BigDecimal traditionalGrossUp, BigDecimal tax) {}

        /**
         * Audit C2: a tax payment sourced from the traditional pool is ITSELF an ordinary-income
         * distribution once withdrawn -- it must fund its own tax, which (once the taxable pool is
         * exhausted) again comes from traditional, and so on: an infinite geometric series that
         * converges because the marginal tax rate is always &lt;1. This method finds that fixed
         * point: {@code bill = TaxBill(baseTaxableIncome + traditionalSlice(bill))}.
         *
         * <p>Convergence strategy (T10 review -- plain cold-start iteration contracts only at rate m
         * per pass and left multi-dollar residuals at realistic marginal rates within the old 5-pass
         * cap):
         * <ol>
         *   <li><b>First pass</b>: take the naive slice {@code implied1 = peek(bill0)} and recompute
         *       the bill at it. If the slice is under the $1 tolerance there is no traditional
         *       exposure at all -- return immediately, byte-identical to pre-fix behavior.</li>
         *   <li><b>Closed-form warm jump</b>: the first pass's two bill evaluations give the exact
         *       chord marginal rate {@code m = (bill1 - bill0) / implied1} over the slice range --
         *       measured through the SAME 5-arg {@code computeOrdinaryTax} seam, so it captures the
         *       combined federal+state rate plus every netting leg exactly (a raw federal-bracket
         *       lookup would understate m whenever state tax is in the bill). Jump straight to the
         *       affine fixed point {@code implied1 / (1 - m)} (capped at
         *       {@link #GROSS_UP_WARM_START_RATE_CAP} and {@code traditionalAvail}) and recompute
         *       there. When no bracket boundary sits inside the jump range this IS the fixed point
         *       and the polish loop below breaks on its first check.</li>
         *   <li><b>Polish loop</b>: plain peek/recompute passes (up to
         *       {@link #MAX_GROSS_UP_ITERATIONS}) correct any bracket-crossing error left by the
         *       jump, breaking when the peeked slice moves by less than
         *       {@link #GROSS_UP_TOLERANCE} -- mirrors {@code YearFinanceResolver}'s Social
         *       Security convergence loop in both shape and tolerance.</li>
         * </ol>
         * {@code peekTraditionalSlice} is a pure function of the FROZEN {@code taxableAvail} /
         * {@code traditionalAvail} snapshots taken just before the tax cascade runs, so no pool is
         * touched during the search.
         *
         * <p>Only the ORDINARY tax bundle is re-stacked each pass; {@code ltcgTax} and {@code
         * extraPoolFundedTax} are added on top unchanged (re-stacking LTCG against a higher ordinary
         * floor as the gross-up grows is a documented, out-of-scope second-order effect, the same
         * category as the taxable-slice realized-gain discard in {@link #deductFromPools}). The Roth
         * slice of a tax payment is never grossed up (Roth withdrawals are tax-free) and the taxable
         * slice keeps its existing untaxed-sale treatment -- this method only ever grows the
         * TRADITIONAL slice.
         *
         * <p>The physical drain happens once, afterward, via the caller's own {@link
         * #deductFromPools} call on the returned {@code tax} -- this method never mutates a pool.
         */
        private GrossUpResult growTraditionalGrossUp(BigDecimal baseTaxableIncome, int year,
                BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal alreadyChargedBaseTax,
                BigDecimal realizedLtcgIncome, BigDecimal federallyTaxedSocialSecurity,
                BigDecimal ltcgTax, BigDecimal extraPoolFundedTax, OrdinaryTax initialOrdinary,
                BigDecimal taxableAvail, BigDecimal traditionalAvail) {

            BigDecimal constantTax = ltcgTax.add(extraPoolFundedTax.max(BigDecimal.ZERO));
            BigDecimal bill0 = initialOrdinary.tax().add(constantTax);

            // First pass: naive slice off the initial bill. Under-tolerance => taxable (plus Roth
            // headroom) covers everything reachable -- no gross-up, exact pre-fix behavior.
            BigDecimal implied1 = peekTraditionalSlice(bill0, taxableAvail, traditionalAvail);
            if (implied1.compareTo(GROSS_UP_TOLERANCE) < 0) {
                return new GrossUpResult(BigDecimal.ZERO, bill0);
            }
            BigDecimal slice = implied1;
            BigDecimal bill = recomputeBill(baseTaxableIncome, slice, year, effectiveOtherIncome,
                    conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity,
                    constantTax);

            // Closed-form warm jump off the measured chord rate (constantTax cancels in the delta).
            BigDecimal chordRate = bill.subtract(bill0)
                    .divide(implied1, SCALE + 4, ROUNDING)
                    .min(GROSS_UP_WARM_START_RATE_CAP);
            if (chordRate.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal jump = implied1
                        .divide(BigDecimal.ONE.subtract(chordRate), SCALE + 4, ROUNDING)
                        .min(traditionalAvail);
                if (jump.subtract(slice).abs().compareTo(GROSS_UP_TOLERANCE) >= 0) {
                    slice = jump;
                    bill = recomputeBill(baseTaxableIncome, slice, year, effectiveOtherIncome,
                            conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome,
                            federallyTaxedSocialSecurity, constantTax);
                }
            }

            // Polish: plain fixed-point passes to absorb any bracket crossing inside the jump range.
            for (int i = 0; i < MAX_GROSS_UP_ITERATIONS; i++) {
                BigDecimal impliedSlice = peekTraditionalSlice(bill, taxableAvail, traditionalAvail);
                if (impliedSlice.subtract(slice).abs().compareTo(GROSS_UP_TOLERANCE) < 0) {
                    break;
                }
                slice = impliedSlice;
                bill = recomputeBill(baseTaxableIncome, slice, year, effectiveOtherIncome,
                        conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome,
                        federallyTaxedSocialSecurity, constantTax);
            }
            return new GrossUpResult(slice, bill);
        }

        /** One gross-up bill evaluation: the ordinary bundle at {@code base + slice} plus the
         * constant (non-re-stacked) ltcg/extra-pool-funded additions. Also refreshes
         * {@code lastTaxBreakdown} via {@code computeOrdinaryTax}'s own recording side effect. */
        private BigDecimal recomputeBill(BigDecimal baseTaxableIncome, BigDecimal slice, int year,
                BigDecimal effectiveOtherIncome, BigDecimal conversionAmount, BigDecimal alreadyChargedBaseTax,
                BigDecimal realizedLtcgIncome, BigDecimal federallyTaxedSocialSecurity,
                BigDecimal constantTax) {
            var recomputed = computeOrdinaryTax(baseTaxableIncome.add(slice), year, effectiveOtherIncome,
                    conversionAmount, alreadyChargedBaseTax, realizedLtcgIncome, federallyTaxedSocialSecurity);
            return recomputed.tax().add(constantTax);
        }

        /**
         * The traditional-sourced dollars of a hypothetical {@code tax} payment cascading
         * taxable-first against FROZEN (pre-drain) pool balances -- mirrors {@link
         * #deductFromPools}'s cascade order without mutating anything, so {@link
         * #growTraditionalGrossUp}'s fixed-point search can explore candidate bills before the real
         * drain happens.
         */
        private static BigDecimal peekTraditionalSlice(BigDecimal tax, BigDecimal taxableAvail,
                                                        BigDecimal traditionalAvail) {
            BigDecimal afterTaxable = tax.subtract(taxableAvail).max(BigDecimal.ZERO);
            return afterTaxable.min(traditionalAvail).max(BigDecimal.ZERO);
        }

        /**
         * Snapshot of MultiPool's mutable state: a deep copy of the taxable FIFO lots plus the
         * scalar traditional/roth balances, this year's booked qualified dividend and (audit C1)
         * ordinary interest, and the last tax breakdown. Restoring returns the pool to its exact
         * post-growth, pre-withdrawal state.
         */
        record MultiPoolMemento(List<BigDecimal[]> lots, Map<PersonId, BigDecimal> traditional,
                                Map<PersonId, BigDecimal> roth, BigDecimal qualifiedDividendIncome,
                                BigDecimal ordinaryInterestIncome,
                                Optional<CombinedTaxResult> lastTaxBreakdown) implements Memento {}

        @Override
        public Memento snapshot() {
            // Household task 4: the memento captures the FULL owner-keyed state (both pools' complete
            // maps, deep-copied so the SS-convergence loop can restore the same starting state
            // repeatedly). BigDecimal is immutable, so copying the map is enough.
            return new MultiPoolMemento(lots.snapshot(), traditional.snapshot(), roth.snapshot(),
                    qualifiedDividendIncome, ordinaryInterestIncome, lastTaxBreakdown);
        }

        @Override
        public void restore(Memento memento) {
            if (memento instanceof MultiPoolMemento m) {
                lots.restore(m.lots());
                this.traditional.restore(m.traditional());
                this.roth.restore(m.roth());
                this.qualifiedDividendIncome = m.qualifiedDividendIncome();
                this.ordinaryInterestIncome = m.ordinaryInterestIncome();
                this.lastTaxBreakdown = m.lastTaxBreakdown();
            }
        }

        /**
         * LTCG tax on {@code realizedGain + qualifiedDividendIncome}, stacked on the year's ordinary
         * taxable income. Returns zero when no capital-gains calculator is wired or the net LTCG income
         * is non-positive (a realized loss nets against the dividend). {@code magi ≈ gross ordinary +
         * LTCG} (MAGI is NOT deduction-reduced), but the STACKING FLOOR is netted by
         * {@link #resolveOrdinaryDeduction} down to the same base {@code ordinaryTaxDetail} (the
         * ordinary-tax result already computed on {@code ordinaryTaxableIncome}, if any) used --
         * otherwise the deduction dollars would be treated as below-the-line for the ordinary tax AND
         * above-the-line for the LTCG floor, overstating LTCG tax.
         */
        private BigDecimal computeLtcgTax(BigDecimal realizedGain, BigDecimal ordinaryTaxableIncome, int year,
                                           CombinedTaxResult ordinaryTaxDetail, BigDecimal netRentalIncome) {
            if (capitalGainsTaxCalculator == null) {
                return BigDecimal.ZERO;
            }
            BigDecimal ltcgIncome = realizedGain.add(qualifiedDividendIncome);
            if (ltcgIncome.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal magi = ordinaryTaxableIncome.add(ltcgIncome);
            BigDecimal deduction = resolveOrdinaryDeduction(ordinaryTaxDetail, year);
            BigDecimal ordinaryForLtcg = ordinaryTaxableIncome.subtract(deduction).max(BigDecimal.ZERO);
            // T18a-3: net rental income joins the NIIT's Net Investment Income base (NOT the LTCG
            // bracket-tax base) -- see CapitalGainsTaxCalculator's rental overload.
            return capitalGainsTaxCalculator.computeLtcgTax(ordinaryForLtcg, ltcgIncome, year,
                    filingStatus, year - baseYear, inflationRate, magi, netRentalIncome);
        }

        /**
         * The deduction the ordinary tax actually used, for netting the LTCG stacking floor onto the
         * same base. Prefers the itemized figure when {@code ordinaryTaxDetail} shows itemizing won
         * (mirrors {@code CombinedTaxCalculator.computeTax}'s own itemized-vs-standard choice);
         * otherwise falls back to the federal standard deduction for (year, filingStatus). Returns ZERO
         * -- i.e. the floor stays gross, pre-fix -- when neither is available (no ordinary-tax result
         * AND no standard-deduction source wired).
         */
        private BigDecimal resolveOrdinaryDeduction(CombinedTaxResult ordinaryTaxDetail, int year) {
            if (ordinaryTaxDetail != null && ordinaryTaxDetail.usedItemized()) {
                return ordinaryTaxDetail.itemizedDeductions();
            }
            if (federalTaxCalculator != null) {
                return federalTaxCalculator.loadStandardDeduction(year, filingStatus);
            }
            return BigDecimal.ZERO;
        }

        @Override
        public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome,
                                                       BigDecimal rmdAmount,
                                                       BigDecimal federallyTaxedSocialSecurity) {
            if (rothConversionStartYear != null && year < rothConversionStartYear) {
                return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }

            BigDecimal effectiveLimit;
            if ("fill_bracket".equals(rothConversionStrategy) && targetBracketRate != null && taxCalculator != null) {
                BigDecimal bracketCeiling = taxCalculator.computeMaxIncomeForTargetRate(
                        targetBracketRate, year, filingStatus);
                // RMD income already claims part of the target bracket, so it leaves less room for
                // conversions -- mirrors WithdrawalOrderStrategy.DynamicSequencingOrder's bracketSpace.
                BigDecimal space = bracketCeiling.subtract(effectiveOtherIncome).subtract(rmdAmount)
                        .max(BigDecimal.ZERO);
                effectiveLimit = space;
            } else {
                effectiveLimit = annualRothConversion;
            }

            if (effectiveLimit.compareTo(BigDecimal.ZERO) <= 0
                    || traditional.total().signum() <= 0) {
                return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }
            return executeConversionWithAmount(effectiveLimit, year, effectiveOtherIncome,
                    federallyTaxedSocialSecurity);
        }

        @Override
        public ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                                BigDecimal overrideAmount, BigDecimal rmdAmount,
                                                                BigDecimal federallyTaxedSocialSecurity) {
            // overrideAmount is an explicit, optimizer-scheduled dollar figure -- like the pre-existing
            // bracket-headroom check it bypasses, it does not respect RMD-consumed headroom either.
            if (overrideAmount.compareTo(BigDecimal.ZERO) <= 0
                    || traditional.total().signum() <= 0) {
                return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }
            return executeConversionWithAmount(overrideAmount, year, effectiveOtherIncome,
                    federallyTaxedSocialSecurity);
        }

        /**
         * Executes a Roth conversion of the given amount: transfers from traditional to Roth,
         * computes tax on the conversion, and deducts tax from pools. Shared by both
         * executeRothConversion (bracket/fixed amount) and executeRothConversionOverride
         * (optimizer-scheduled amount). The state base exempts the federally-taxed Social Security
         * amount (audit C3) but adds no LTCG income -- the year's realized LTCG/dividends are taxed
         * once, in {@link #executeWithdrawals}'s bundle; the marginal subtraction there recomputes
         * THIS base with identical arguments so the netting is exact.
         */
        private ConversionResult executeConversionWithAmount(BigDecimal conversionLimit, int year,
                                                               BigDecimal effectiveOtherIncome,
                                                               BigDecimal federallyTaxedSocialSecurity) {
            // Household task 4: draw the conversion from traditional split proportionally by owner,
            // and credit each owner's OWN Roth with what came from that owner's traditional (a Roth
            // conversion stays within one person's accounts) — conservation-preserving and
            // byte-identical for a single owner.
            BigDecimal actual = conversionLimit.min(traditional.total());
            var converted = traditional.debitProportional(actual);
            for (var entry : converted.entrySet()) {
                roth.credit(entry.getKey(), entry.getValue());
            }

            if (taxCalculator != null) {
                BigDecimal taxableIncome = actual.add(effectiveOtherIncome);
                var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus,
                        BigDecimal.ZERO, federallyTaxedSocialSecurity);
                BigDecimal tax = detailed.totalTax();
                lastTaxBreakdown = Optional.of(detailed);
                TaxSourceResult taxSource = deductFromPools(tax);
                return new ConversionResult(actual, tax, taxSource);
            }
            return new ConversionResult(actual, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }

        @Override
        public BigDecimal forceRmd(BigDecimal rmdAmount) {
            if (rmdAmount == null || rmdAmount.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal forced = rmdAmount.min(traditional.total()).max(BigDecimal.ZERO);
            if (forced.compareTo(BigDecimal.ZERO) > 0) {
                traditional.debitProportional(forced);
                lots.addLot(forced);
            }
            return forced;
        }

        @Override
        public BigDecimal forceRmd(PersonId owner, BigDecimal rmdAmount) {
            if (rmdAmount == null || rmdAmount.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal forced = traditional.forceOwner(owner, rmdAmount);
            if (forced.compareTo(BigDecimal.ZERO) > 0) {
                lots.addLot(forced);
            }
            return forced;
        }

        @Override
        public void applyFirstDeathTransition(PersonId deceased, PersonId survivor, boolean communityProperty) {
            // Step 2 (spec order): spousal rollover — deceased's trad/roth transfer to survivor.
            // Conservation-preserving (transferAll moves, never mints); the tax cascade thereafter
            // sees one combined per-owner slice under the survivor.
            traditional.transferAll(deceased, survivor);
            roth.transferAll(deceased, survivor);
            // Step 3: EXACT per-owner basis step-up on the commingled taxable lots (HP1). Each lot
            // steps by its own owner's statutory rate — joint at the community/common-law rate, the
            // decedent's own lots fully, the survivor's own lots not at all — and decedent-owned lots
            // retag to the survivor. Replaces the T5 single initial-balance-weighted blended factor.
            BigDecimal jointFactor = communityProperty ? BigDecimal.ONE : new BigDecimal("0.5");
            lots.stepUpByOwner(LotOwner.forPerson(deceased), jointFactor);
            // Step 5: filing status flips to single for this and every later year.
            this.filingStatus = FilingStatus.SINGLE;
        }

        @Override
        public void floorAtZero() {
            // The taxable lots can never go negative (sellFifo caps every draw at the current total);
            // only the traditional/Roth pools can be driven below zero by the tax cascade. Each owner's
            // slice is floored independently (task 4).
            traditional.floorAtZero();
            roth.floorAtZero();
        }

        @Override
        public Optional<CombinedTaxResult> getLastTaxBreakdown() {
            return lastTaxBreakdown;
        }

        @Override
        public void depositToTaxable(BigDecimal amount) {
            lots.addLot(amount);   // reinvested surplus enters at cost (basis = value)
        }

        @Override
        public ProjectionYearDto buildYearDto(YearDtoContext ctx) {
            var inputs = new MultiPoolYearDtoBuilder.YearDtoInputs(
                    ctx.year(), ctx.age(), ctx.startBalance(), ctx.contributions(), ctx.totalGrowth(),
                    ctx.withdrawals(), ctx.retired(),
                    ctx.conversionAmount(), ctx.taxLiability(), ctx.growthResult(),
                    ctx.withdrawalFromTaxable(), ctx.withdrawalFromTraditional(), ctx.withdrawalFromRoth(),
                    ctx.combinedTaxSource(), getTotal(), lots.totalValue(), traditional.total(), roth.total(),
                    ctx.rmdAmount(), ctx.ltcgTax(), ctx.earlyWithdrawalPenalty());

            // The breakdown is consumed once per year, then cleared so the next year starts fresh.
            CombinedTaxResult breakdown = lastTaxBreakdown.orElse(null);
            lastTaxBreakdown = Optional.empty();
            return MultiPoolYearDtoBuilder.build(inputs, breakdown);
        }

        @Override
        public BigDecimal getMagi() {
            return otherIncome;
        }

        @Override
        public FilingStatus getFilingStatus() {
            return filingStatus;
        }

        @Override
        public boolean processIncomeSourcesEveryYear() {
            return true;
        }

        @Override
        public boolean tracksSETax() {
            return true;
        }

        @Override
        public BigDecimal computeEffectiveOtherIncome(BigDecimal activeIncome, BigDecimal incomeSourceCash) {
            return otherIncome.add(activeIncome).add(incomeSourceCash);
        }

        @Override
        public String logTag() {
            return "Projection with pools";
        }

        /**
         * Physically drains {@code amount} taxable-first, then traditional, then Roth. Callers that
         * fund an ordinary/LTCG tax bill run {@link #growTraditionalGrossUp} FIRST so the traditional
         * slice this cascade is about to drain has already been grossed up and folded into the
         * year's reported ordinary income (audit C2) -- by the time {@code amount} reaches here it is
         * the FINAL, already-grossed-up bill; this method itself stays a plain three-pool drain.
         */
        private TaxSourceResult deductFromPools(BigDecimal amount) {
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                return TaxSourceResult.ZERO;
            }
            BigDecimal remaining = amount;

            // Pay from taxable first, selling lots FIFO. The gain realized by this tax-payment sale
            // is deliberately not itself taxed (a second-order effect out of scope for this model).
            BigDecimal fromTax = remaining.min(lots.totalValue());
            lots.sellFifo(fromTax);
            remaining = remaining.subtract(fromTax);

            // Traditional and Roth slices of the bill split proportionally by owner balance (task 4);
            // the Roth catch-all can drive an owner negative, which floorAtZero later clears.
            BigDecimal fromTrad = remaining.min(traditional.total());
            traditional.debitProportional(fromTrad);
            remaining = remaining.subtract(fromTrad);

            roth.debitProportional(remaining);
            return new TaxSourceResult(fromTax, fromTrad, remaining);
        }
    }
}
