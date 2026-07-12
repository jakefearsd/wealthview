package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
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

    /** Account type keys used for grouping and pool map lookups. */
    static final String POOL_TAXABLE = "taxable";
    static final String POOL_TRADITIONAL = "traditional";
    static final String POOL_ROTH = "roth";

    /** Withdrawal order string for the dynamic sequencing strategy. */
    static final String WITHDRAWAL_ORDER_DYNAMIC_SEQUENCING = "dynamic_sequencing";

    BigDecimal getTotal();

    /** The traditional (pre-tax) pool balance; zero for strategies with no traditional/Roth split. */
    BigDecimal getTraditional();

    BigDecimal getWeightedReturn();

    BigDecimal applyContributions();

    GrowthResult applyGrowth();

    /**
     * Back-compat overload: no explicit signal that this year's base-income tax was already
     * charged elsewhere, and no additional tax obligation to fund from the pool cascade. Callers
     * that don't participate in {@link RetirementWithdrawalProcessor}'s surplus-tax bookkeeping
     * (audit A4) keep their exact pre-fix behavior via this overload -- see the 9-arg method's
     * javadoc for the full contract.
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
     * @param rmdAmount (T18a-1) the amount of this year's RMD ALREADY forced out of traditional by
     *     a prior {@link #forceRmd} call — this method itself no longer forces anything out of the
     *     pool for RMD purposes. It is folded straight into the year's traditional-sourced ordinary
     *     income for tax attribution, exactly like the spend draw's own traditional portion. Zero
     *     when no RMD applies this year.
     * @param alreadyChargedBaseTax the dollar amount of ordinary tax on {@code (effectiveOtherIncome
     *     + conversionAmount)} that {@link RetirementWithdrawalProcessor}'s spending-plan surplus
     *     branch has ALREADY charged this year (whether funded from surplus cash or left for
     *     {@code extraPoolFundedTax} below) -- an EXPLICIT signal that replaces the old
     *     {@code totalNeed <= 0} ("noSpendDraw") proxy (audit A4 / T2 review). The old proxy
     *     assumed {@code totalNeed<=0 ⟺ the surplus branch charged tax}, which holds for
     *     {@code TierBasedSpendingPlan} (its resolved {@code portfolioWithdrawal} is derived from
     *     the SAME {@code spendingNeed - activeIncome} quantities that decide the surplus branch)
     *     but NOT for {@code GuardrailSpendingInput}, whose {@code resolveYear} returns a
     *     pre-computed {@code portfolioWithdrawal} that ignores live income entirely -- so its
     *     sign can disagree with whether the surplus branch actually ran. Zero when no such
     *     charge exists (deficit years, no spending plan, or the Guardrail-mismatch case): the
     *     caller has NOT pre-charged anything, so the bundle below must charge it in full.
     *     IMPORTANT (audit C3): when non-zero, the caller must have computed it with the SAME
     *     state-base adjustments this method applies (same {@code federallyTaxedSocialSecurity},
     *     zero LTCG income) so the marginal subtraction below nets exactly and stays monotone.
     * @param extraPoolFundedTax additional tax obligation (the surplus branch's tax remainder once
     *     the year's surplus was insufficient to cover it, and/or self-employment tax, which has
     *     no bundle of its own anywhere else this year) that must be funded from the SAME pool
     *     cascade as the withdrawal-tax bundle, on top of it -- audit A4's "route the unfunded
     *     remainder through deductFromPools" fix. Zero when nothing is outstanding.
     * @param federallyTaxedSocialSecurity the year's converged federally-taxable Social Security
     *     amount (audit B2's fixed point), threaded into the ordinary-tax bundle's state-base
     *     computation so states that exempt Social Security don't tax it (audit C3). The realized
     *     LTCG/dividend state addition needs no parameter -- it is computed internally from the
     *     FIFO sale and the year's booked dividend. Zero when no Social Security is taxable.
     */
    default WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount, BigDecimal rmdAmount, int age,
                                           BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                                           BigDecimal federallyTaxedSocialSecurity) {
        return executeWithdrawals(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                alreadyChargedBaseTax, extraPoolFundedTax, federallyTaxedSocialSecurity, BigDecimal.ZERO);
    }

    /**
     * As the 9-arg {@link #executeWithdrawals(BigDecimal, int, BigDecimal, BigDecimal, BigDecimal,
     * int, BigDecimal, BigDecimal, BigDecimal)}, additionally threading {@code netRentalIncome}
     * (T18a-3) into the year's LTCG/NIIT bundle's Net Investment Income base -- see
     * {@code CapitalGainsTaxCalculator#computeLtcgTax}'s rental overload for the mechanics. Zero
     * when no rental income sources are active.
     */
    WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount, BigDecimal rmdAmount, int age,
                                           BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax,
                                           BigDecimal federallyTaxedSocialSecurity, BigDecimal netRentalIncome);

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
     */
    record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability,
                               BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth,
                               TaxSourceResult taxSource, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome,
                               BigDecimal earlyWithdrawalPenalty) {}

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
            BigDecimal feeRate,
            int baseYear,
            FederalTaxCalculator federalTaxCalculator) {

        /**
         * Back-compat constructor for callers that predate allocation-driven returns: uses an
         * empty capital-market map and zero inflation, so returns come solely from per-account
         * overrides (the legacy {@code expectedReturn} path). No capital-gains calculator and a
         * zero dividend yield, so the taxable pool tracks cost basis but realizes no LTCG tax and
         * no dividend drag — the pre-lots scalar behavior, bit-for-bit. Zero fee rate for the same
         * reason (audit B1) — these legacy callers stay fee-free.
         */
        PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                   String rothConversionStrategy, BigDecimal targetBracketRate,
                   Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                   TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate) {
            this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                    rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                    Map.of(), BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
        }

        /**
         * Back-compat constructor for allocation-driven callers that predate capital-gains taxation:
         * supplies capital-market means and inflation but no LTCG calculator / dividend yield / fee
         * rate / federal standard-deduction source.
         */
        PoolConfig(FilingStatus filingStatus, BigDecimal otherIncome, BigDecimal annualRothConversion,
                   String rothConversionStrategy, BigDecimal targetBracketRate,
                   Integer rothConversionStartYear, WithdrawalOrder withdrawalOrder,
                   TaxCalculationStrategy taxCalculator, BigDecimal dynamicSequencingBracketRate,
                   Map<AssetClass, Double> geoMeans, BigDecimal inflationRate) {
            this(filingStatus, otherIncome, annualRothConversion, rothConversionStrategy, targetBracketRate,
                    rothConversionStartYear, withdrawalOrder, taxCalculator, dynamicSequencingBracketRate,
                    geoMeans, inflationRate, null, BigDecimal.ZERO, BigDecimal.ZERO, 0, null);
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
        Map<String, List<ProjectionAccountInput>> grouped = accounts.stream()
                .collect(Collectors.groupingBy(ProjectionAccountInput::accountType));

        BigDecimal totalBalance = sumInitialBalances(grouped.getOrDefault(POOL_TAXABLE, List.of()))
                .add(sumInitialBalances(grouped.getOrDefault(POOL_TRADITIONAL, List.of())))
                .add(sumInitialBalances(grouped.getOrDefault(POOL_ROTH, List.of())));

        return new MultiPool(grouped,
                poolWeightedReturn(grouped.getOrDefault(POOL_TAXABLE, List.of()), geoMeans, inflationRate,
                        feeRate),
                poolWeightedReturn(grouped.getOrDefault(POOL_TRADITIONAL, List.of()), geoMeans, inflationRate,
                        feeRate),
                poolWeightedReturn(grouped.getOrDefault(POOL_ROTH, List.of()), geoMeans, inflationRate,
                        feeRate),
                computeWeightedReturn(accounts, totalBalance, geoMeans, inflationRate, feeRate),
                config);
    }

    private static BigDecimal sumInitialBalances(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .map(ProjectionAccountInput::initialBalance)
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
        private BigDecimal traditional;
        private BigDecimal roth;
        private Optional<CombinedTaxResult> lastTaxBreakdown = Optional.empty();
        /**
         * The current year's qualified-dividend income (value × dividend yield), booked in
         * {@link #applyGrowth()} and consumed as LTCG income in {@link #executeWithdrawals}. Reset
         * each {@code applyGrowth}; during accumulation it is never consumed (the model has no wage
         * income to stack it on) so only retirement-year dividends are taxed.
         */
        private BigDecimal qualifiedDividendIncome = BigDecimal.ZERO;

        private final BigDecimal tradContrib;
        private final BigDecimal rothContrib;
        private final BigDecimal taxableContrib;
        private final BigDecimal taxableReturn;
        private final BigDecimal traditionalReturn;
        private final BigDecimal rothReturn;
        private final BigDecimal weightedReturn;

        private final FilingStatus filingStatus;
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
        MultiPool(Map<String, List<ProjectionAccountInput>> grouped,
                  BigDecimal weightedReturn,
                  PoolConfig config) {
            this(grouped, weightedReturn, weightedReturn, weightedReturn, weightedReturn, config);
        }

        MultiPool(Map<String, List<ProjectionAccountInput>> grouped,
                  BigDecimal taxableReturn, BigDecimal traditionalReturn, BigDecimal rothReturn,
                  BigDecimal weightedReturn,
                  PoolConfig config) {
            // Seed one FIFO lot per taxable account: basis = its cost basis, value = its balance,
            // so any embedded (unrealized) gain is carried into the projection.
            this.lots = new TaxableLotsBd();
            for (var acct : grouped.getOrDefault(POOL_TAXABLE, List.of())) {
                lots.addLot(acct.costBasis(), acct.initialBalance());
            }
            this.traditional = sumBalances(grouped.getOrDefault(POOL_TRADITIONAL, List.of()));
            this.roth = sumBalances(grouped.getOrDefault(POOL_ROTH, List.of()));

            this.tradContrib = sumContribs(grouped.getOrDefault(POOL_TRADITIONAL, List.of()));
            this.rothContrib = sumContribs(grouped.getOrDefault(POOL_ROTH, List.of()));
            this.taxableContrib = sumContribs(grouped.getOrDefault(POOL_TAXABLE, List.of()));

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
            this.inflationRate = config.inflationRate();
            this.baseYear = config.baseYear();
            this.federalTaxCalculator = config.federalTaxCalculator();
        }

        private static BigDecimal sumBalances(List<ProjectionAccountInput> accounts) {
            return accounts.stream()
                    .map(ProjectionAccountInput::initialBalance)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private static BigDecimal sumContribs(List<ProjectionAccountInput> accounts) {
            return accounts.stream()
                    .map(ProjectionAccountInput::annualContribution)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        @Override
        public BigDecimal getTotal() {
            return lots.totalValue().add(traditional).add(roth);
        }

        @Override
        public BigDecimal getTraditional() {
            return traditional;
        }

        @Override
        public BigDecimal getWeightedReturn() {
            return weightedReturn;
        }

        @Override
        public BigDecimal applyContributions() {
            traditional = traditional.add(tradContrib);
            roth = roth.add(rothContrib);
            lots.addLot(taxableContrib);   // new contributions enter at cost (basis = value)
            return tradContrib.add(rothContrib).add(taxableContrib);
        }

        @Override
        public GrowthResult applyGrowth() {
            BigDecimal tradGrowth = traditional.multiply(traditionalReturn).setScale(SCALE, ROUNDING);
            BigDecimal rothGrowth = roth.multiply(rothReturn).setScale(SCALE, ROUNDING);

            // Split the taxable return: existing lots appreciate at (r − dividendYield); the
            // dividend (≈ value × dividendYield) is reinvested as a fresh at-cost lot. The dividend
            // is booked as the residual to the exact target total, so the taxable pool still grows
            // at precisely r (bit-identical to the pre-lots scalar path when dividendYield is 0).
            // The dividend becomes this year's qualifiedDividendIncome and is TAXED only at
            // withdrawal time (retirement); during accumulation it resets each year unconsumed.
            BigDecimal taxableBefore = lots.totalValue();
            BigDecimal taxableGrowth = taxableBefore.multiply(taxableReturn).setScale(SCALE, ROUNDING);
            BigDecimal targetTotal = taxableBefore.add(taxableGrowth);
            lots.grow(taxableReturn.subtract(dividendYield));
            BigDecimal dividend = targetTotal.subtract(lots.totalValue());
            lots.addLot(dividend);
            qualifiedDividendIncome = dividend.max(BigDecimal.ZERO);

            traditional = traditional.add(tradGrowth);
            roth = roth.add(rothGrowth);
            return new GrowthResult(tradGrowth.add(rothGrowth).add(taxableGrowth),
                    taxableGrowth, tradGrowth, rothGrowth);
        }

        @Override
        public WithdrawalTaxResult executeWithdrawals(BigDecimal totalNeed, int year,
                                                      BigDecimal effectiveOtherIncome,
                                                      BigDecimal conversionAmount,
                                                      BigDecimal rmdAmount, int age,
                                                      BigDecimal alreadyChargedBaseTax,
                                                      BigDecimal extraPoolFundedTax,
                                                      BigDecimal federallyTaxedSocialSecurity,
                                                      BigDecimal netRentalIncome) {
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
                        strategy.execute(totalNeed, lots.totalValue(), traditional, roth);
                if (allocation != null) {
                    fromTaxable = allocation.fromTaxable();
                    fromTraditional = allocation.fromTraditional();
                    fromRoth = allocation.fromRoth();
                }
            }

            // Selling the taxable draw FIFO realizes a long-term capital gain (oldest lots first).
            BigDecimal realizedGain = lots.sellFifo(fromTaxable);
            traditional = traditional.subtract(fromTraditional);
            roth = roth.subtract(fromRoth);

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

            BigDecimal taxableIncome = traditionalOrdinaryIncome.add(effectiveOtherIncome).add(conversionAmount);
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
                    extraPoolFundedTax, ordinaryTax, lots.totalValue(), traditional);
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
                    realizedLtcgIncome, earlyWithdrawalPenalty);
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
         * scalar traditional/roth balances, this year's booked qualified dividend, and the last tax
         * breakdown. Restoring returns the pool to its exact post-growth, pre-withdrawal state.
         */
        record MultiPoolMemento(List<BigDecimal[]> lots, BigDecimal traditional,
                                BigDecimal roth, BigDecimal qualifiedDividendIncome,
                                Optional<CombinedTaxResult> lastTaxBreakdown) implements Memento {}

        @Override
        public Memento snapshot() {
            return new MultiPoolMemento(lots.snapshot(), traditional, roth,
                    qualifiedDividendIncome, lastTaxBreakdown);
        }

        @Override
        public void restore(Memento memento) {
            if (memento instanceof MultiPoolMemento m) {
                lots.restore(m.lots());
                this.traditional = m.traditional();
                this.roth = m.roth();
                this.qualifiedDividendIncome = m.qualifiedDividendIncome();
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
                    || traditional.compareTo(BigDecimal.ZERO) <= 0) {
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
                    || traditional.compareTo(BigDecimal.ZERO) <= 0) {
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
            BigDecimal actual = conversionLimit.min(traditional);
            traditional = traditional.subtract(actual);
            roth = roth.add(actual);

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
            BigDecimal forced = rmdAmount.min(traditional).max(BigDecimal.ZERO);
            if (forced.compareTo(BigDecimal.ZERO) > 0) {
                traditional = traditional.subtract(forced);
                lots.addLot(forced);
            }
            return forced;
        }

        @Override
        public void floorAtZero() {
            // The taxable lots can never go negative (sellFifo caps every draw at the current total);
            // only the scalar traditional/roth pools can be driven below zero by the tax cascade.
            traditional = traditional.max(BigDecimal.ZERO);
            roth = roth.max(BigDecimal.ZERO);
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
                    ctx.combinedTaxSource(), getTotal(), lots.totalValue(), traditional, roth,
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

            BigDecimal fromTrad = remaining.min(traditional);
            traditional = traditional.subtract(fromTrad);
            remaining = remaining.subtract(fromTrad);

            roth = roth.subtract(remaining);
            return new TaxSourceResult(fromTax, fromTrad, remaining);
        }
    }
}
