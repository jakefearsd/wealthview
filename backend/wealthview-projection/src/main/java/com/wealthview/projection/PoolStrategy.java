package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.household.PersonId;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.FilingStatus;

/**
 * Strategy for managing investment pool balances during projection year-loop. {@link MultiPool} is
 * the sole implementation: it manages traditional/roth/taxable sub-pools, with empty pools for any
 * account type absent from the scenario (audit C11 -- an all-taxable scenario is simply a MultiPool
 * with zero traditional and zero roth, not a distinct untaxed code path; see {@link #create}).
 */
// PMD.ExcessivePublicCount: still 41 methods plus 3 constants (checked by removing this suppression after the
// account-return statics moved to PoolReturnBlender: the rule still fires). An interface's members are implicitly
// public, so none can be narrowed to package-private; the interface IS the single seam between the year loop and
// the pools, and 8 of the members are the bond-tent hooks, so the count is the seam's width rather than leaked
// internals.
@SuppressWarnings("PMD.ExcessivePublicCount")
sealed interface PoolStrategy permits MultiPool {

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
     * Bond tent configuration (spec 4.3): where the tent is placed, the mix it is split by, the real
     * returns its sleeves grow at (already net of the scenario fee rate) and the user's typed
     * nominal yields (used only for taxable interest).
     */
    record TentSetup(List<PoolType> placementOrder, Map<BondMaturity, BigDecimal> mixShares,
                     Map<BondMaturity, BigDecimal> realReturns, Map<BondMaturity, BigDecimal> nominalYields) {

        /** Copies the collections, so a caller mutating its own list or maps afterwards cannot change the setup. */
        public TentSetup {
            placementOrder = List.copyOf(placementOrder);
            mixShares = Map.copyOf(mixShares);
            realReturns = Map.copyOf(realReturns);
            nominalYields = Map.copyOf(nominalYields);
        }

        /** The tent is placed in the pools' own withdrawal-priority sequence (spec rule 7). */
        static List<PoolType> placementFor(WithdrawalOrder order) {
            return order.drawSequence();
        }
    }

    /** The bond-tent wiring; the four tent operations below delegate to it. */
    TentPools tentPools();

    /** Enables the tent; until called, the three tent methods below are no-ops / zero. */
    default void configureTent(TentSetup setup) {
        tentPools().configure(setup);
    }

    /** Reserves {@code max(0, target - reserved)} more in the placement order, up to each pool's free balance. */
    default void topUpTent(BigDecimal targetBalance) {
        tentPools().topUp(targetBalance);
    }

    /** Reserves like {@link #topUpTent(BigDecimal)} but at most {@code cap} more; a null cap is uncapped. */
    default void topUpTent(BigDecimal targetBalance, @Nullable BigDecimal cap) {
        tentPools().topUp(targetBalance, cap);
    }

    /** Spends {@code min(amount, tent)}, shortest maturity first; returns what was spent (0 without a tent). */
    default BigDecimal spendTent(BigDecimal amount) {
        return tentPools().spend(amount);
    }

    /** The unreserved part's balance-weighted return at the pools' current returns; 0 when none is left. */
    default BigDecimal growthPartReturn() {
        return tentPools().growthPartReturn();
    }

    /**
     * Grows later years at {@code returns} (per pool) and the tent's sleeves at {@code sleeveReturns}
     * (already net of fee). {@link #getWeightedReturn()} is unchanged.
     */
    default void useYearReturns(PoolReturns returns, Map<BondMaturity, BigDecimal> sleeveReturns) {
        tentPools().useYearReturns(returns, sleeveReturns);
    }

    /** Per-pool returns for one set of asset-class returns; see {@link PoolReturnBlender#poolReturnsFor}. */
    record PoolReturns(BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
    }

    /** The reserved tent balance after clamping each pool's reservation to its balance. */
    default BigDecimal tentTotal() {
        return tentPools().total();
    }

    /** The reserved tent balance by maturity (shortest first); empty until a tent is configured. */
    default Map<BondMaturity, BigDecimal> tentByMaturity() {
        return tentPools().byMaturity();
    }

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
     * and counts toward RMD satisfaction. See {@code MultiPool#growTraditionalGrossUp}
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

    /** D5: true when a tax-funding taxable sale earlier this year realized a gain (or loss) that the
     * year's withdrawal cycle has not yet taxed -- lets a not-yet-retired conversion year run the
     * zero-need withdrawal cycle so that gain is taxed in the year it was realized. */
    boolean hasPendingTaxSaleGain();

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
     * the pool's early-access age ({@code AgeMilestones}). It is ALREADY folded into {@code taxLiability}
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
        Map<PoolType, List<ProjectionAccountInput>> grouped = PoolReturnBlender.groupByPool(accounts);
        BigDecimal household = PoolReturnBlender.blendedRealReturn(accounts, config.geoMeans(),
                config.inflationRate(), config.feeRate());
        var returns = PoolReturnBlender.poolReturns(grouped, household, config.geoMeans(), config.inflationRate(),
                config.feeRate());
        return new MultiPool(grouped, returns.taxable(), returns.traditional(), returns.roth(), household, config);
    }
}
