package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
 * Strategy for managing investment pool balances during projection year-loop.
 * SinglePool manages a single aggregate balance; MultiPool manages traditional/roth/taxable.
 */
sealed interface PoolStrategy permits PoolStrategy.SinglePool, PoolStrategy.MultiPool {

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
     * (audit A4) keep their exact pre-fix behavior via this overload -- see the 8-arg method's
     * javadoc for the full contract.
     */
    default WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                                    BigDecimal conversionAmount, BigDecimal rmdAmount, int age) {
        return executeWithdrawals(need, year, effectiveOtherIncome, conversionAmount, rmdAmount, age,
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /**
     * Executes the year's withdrawal/RMD/tax cascade.
     *
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
     * @param extraPoolFundedTax additional tax obligation (the surplus branch's tax remainder once
     *     the year's surplus was insufficient to cover it, and/or self-employment tax, which has
     *     no bundle of its own anywhere else this year) that must be funded from the SAME pool
     *     cascade as the withdrawal-tax bundle, on top of it -- audit A4's "route the unfunded
     *     remainder through deductFromPools" fix. Zero when nothing is outstanding.
     */
    WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount, BigDecimal rmdAmount, int age,
                                           BigDecimal alreadyChargedBaseTax, BigDecimal extraPoolFundedTax);

    ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome, BigDecimal rmdAmount);

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
                          BigDecimal rmdAmount, BigDecimal ltcgTax) {}

    default ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                            BigDecimal overrideAmount, BigDecimal rmdAmount) {
        return executeRothConversion(year, effectiveOtherIncome, rmdAmount);
    }

    void floorAtZero();

    /**
     * Deposits a surplus amount into the taxable account (or aggregate balance for SinglePool).
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
     * {@code ltcgTax} is the long-term capital-gains portion of {@code taxLiability} (zero for
     * {@link SinglePool}, which tracks no cost basis). It is broken out separately so the engine
     * can fold it into the year's federal-tax breakdown -- see {@link RetirementTaxAnnotator}.
     *
     * <p>{@code realizedLtcgIncome} is the year's realized long-term capital-gains + qualified-
     * dividend INCOME (not tax), floored at zero. The Social Security provisional-income convergence
     * (audit B2) folds it into AGI-ex-SS alongside {@code fromTraditional} and the Roth conversion.
     */
    record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability,
                               BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth,
                               TaxSourceResult taxSource, BigDecimal ltcgTax, BigDecimal realizedLtcgIncome) {}

    /**
     * Opaque, per-implementation snapshot of a pool's mutable state, taken AFTER the year's growth
     * and BEFORE income/conversion/withdrawal, so the deterministic engine's Social Security
     * provisional-income fixed-point loop (audit B2) can re-run those steps from an identical
     * starting state each iteration.
     */
    sealed interface Memento permits SinglePool.SinglePoolMemento, MultiPool.MultiPoolMemento {}

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
     * Factory method that decides whether to create a SinglePool or MultiPool based on the
     * account types present, and encapsulates all construction details.
     */
    static PoolStrategy create(List<ProjectionAccountInput> accounts, PoolConfig config) {
        Map<AssetClass, Double> geoMeans = config.geoMeans();
        BigDecimal inflationRate = config.inflationRate();
        BigDecimal feeRate = config.feeRate();
        if (hasMultipleAccountTypes(accounts)) {
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
        } else {
            BigDecimal balance = sumInitialBalances(accounts);
            return new SinglePool(balance, sumContributions(accounts),
                    computeWeightedReturn(accounts, balance, geoMeans, inflationRate, feeRate));
        }
    }

    private static boolean hasMultipleAccountTypes(List<ProjectionAccountInput> accounts) {
        long distinctTypes = accounts.stream()
                .map(ProjectionAccountInput::accountType)
                .distinct()
                .count();
        boolean hasNonTaxable = accounts.stream()
                .anyMatch(a -> !POOL_TAXABLE.equals(a.accountType()));
        return distinctTypes > 1 || hasNonTaxable;
    }

    private static BigDecimal sumInitialBalances(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
                .map(ProjectionAccountInput::initialBalance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumContributions(List<ProjectionAccountInput> accounts) {
        return accounts.stream()
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

    // --- SinglePool ---

    final class SinglePool implements PoolStrategy {
        private BigDecimal balance;
        private final BigDecimal totalContributions;
        private final BigDecimal weightedReturn;

        SinglePool(BigDecimal balance, BigDecimal totalContributions, BigDecimal weightedReturn) {
            this.balance = balance;
            this.totalContributions = totalContributions;
            this.weightedReturn = weightedReturn;
        }

        @Override
        public BigDecimal getTotal() {
            return balance;
        }

        @Override
        public BigDecimal getTraditional() {
            return BigDecimal.ZERO;
        }

        @Override
        public BigDecimal getWeightedReturn() {
            return weightedReturn;
        }

        @Override
        public BigDecimal applyContributions() {
            balance = balance.add(totalContributions);
            return totalContributions;
        }

        @Override
        public GrowthResult applyGrowth() {
            BigDecimal growth = balance.multiply(weightedReturn).setScale(SCALE, ROUNDING);
            balance = balance.add(growth);
            return new GrowthResult(growth, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        }

        @Override
        public WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year,
                                                      BigDecimal effectiveOtherIncome,
                                                      BigDecimal conversionAmount,
                                                      BigDecimal rmdAmount, int age,
                                                      BigDecimal alreadyChargedBaseTax,
                                                      BigDecimal extraPoolFundedTax) {
            // Simple path: withdrawal is just min(need, balance); SinglePool tracks no ordinary-tax
            // bundle to net alreadyChargedBaseTax against (see interface javadoc). extraPoolFundedTax
            // (audit A4: SE tax / an unfunded surplus-tax remainder) still has to leave the balance,
            // otherwise it would vanish here exactly as it used to for MultiPool.
            BigDecimal withdrawn = need.min(balance);
            balance = balance.subtract(withdrawn);
            BigDecimal tax = extraPoolFundedTax.max(BigDecimal.ZERO);
            if (tax.compareTo(BigDecimal.ZERO) > 0) {
                balance = balance.subtract(tax);
            }
            return new WithdrawalTaxResult(withdrawn, tax,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO);
        }

        /** SinglePool holds a single scalar balance, so its memento is just that value. */
        record SinglePoolMemento(BigDecimal balance) implements Memento {}

        @Override
        public Memento snapshot() {
            return new SinglePoolMemento(balance);
        }

        @Override
        public void restore(Memento memento) {
            if (memento instanceof SinglePoolMemento m) {
                this.balance = m.balance();
            }
        }

        @Override
        public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome,
                                                       BigDecimal rmdAmount) {
            // No-op for single pool
            return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }

        @Override
        public void floorAtZero() {
            if (balance.compareTo(BigDecimal.ZERO) < 0) {
                balance = BigDecimal.ZERO;
            }
        }

        @Override
        public void depositToTaxable(BigDecimal amount) {
            balance = balance.add(amount);
        }

        @Override
        public ProjectionYearDto buildYearDto(YearDtoContext ctx) {
            return ProjectionYearDto.simple(ctx.year(), ctx.age(), ctx.startBalance(), ctx.contributions(),
                    ctx.totalGrowth(), ctx.withdrawals(), balance, ctx.retired());
        }

        @Override
        public BigDecimal getMagi() {
            return BigDecimal.ZERO;
        }

        @Override
        public FilingStatus getFilingStatus() {
            return FilingStatus.SINGLE;
        }

        @Override
        public boolean processIncomeSourcesEveryYear() {
            return false;
        }

        @Override
        public boolean tracksSETax() {
            return false;
        }

        @Override
        public BigDecimal computeEffectiveOtherIncome(BigDecimal activeIncome, BigDecimal incomeSourceCash) {
            return BigDecimal.ZERO;
        }

        @Override
        public String logTag() {
            return "Projection";
        }
    }

    // --- MultiPool ---

    final class MultiPool implements PoolStrategy {
        /**
         * The taxable pool, tracked as FIFO cost-basis lots so withdrawals can realize long-term
         * capital gains. Replaces the pre-lots scalar {@code taxable} balance; its {@code totalValue()}
         * is the taxable balance everywhere the scalar was read.
         */
        private final TaxableLotsBd lots;
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
                                                      BigDecimal extraPoolFundedTax) {
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

            // If the RMD exceeds what the spend draw already pulled from traditional, force the
            // excess out physically: it's a real, legally-required distribution even when the
            // retiree doesn't need the cash. The gross excess is reinvested to taxable (a fresh
            // at-cost lot), and the tax on the full distribution flows through the deductFromPools
            // cascade below, which draws from taxable first -- i.e. from the RMD proceeds just added.
            BigDecimal rmdExtra = BigDecimal.ZERO;
            if (rmdAmount != null && rmdAmount.compareTo(fromTraditional) > 0) {
                rmdExtra = rmdAmount.subtract(fromTraditional).min(traditional).max(BigDecimal.ZERO);
                traditional = traditional.subtract(rmdExtra);
                lots.addLot(rmdExtra);
            }
            BigDecimal traditionalOrdinaryIncome = fromTraditional.add(rmdExtra);

            BigDecimal withdrawalTax = BigDecimal.ZERO;
            BigDecimal taxableIncome = traditionalOrdinaryIncome.add(effectiveOtherIncome).add(conversionAmount);
            CombinedTaxResult detailed = null;
            if (taxableIncome.compareTo(BigDecimal.ZERO) > 0 && taxCalculator != null) {
                detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus);

                // Net out tax that was ALREADY charged elsewhere this year, to avoid double-counting
                // it through separate progressive-bracket calculations:
                //  - conversionAmount > 0: its tax was already computed and paid by
                //    executeConversionWithAmount (called earlier in the year) -- recompute the base
                //    tax fresh (bracket-accurate marginal subtraction) since that call's own paid
                //    amount isn't threaded through here.
                //  - alreadyChargedBaseTax > 0: RetirementWithdrawalProcessor's surplus branch (see
                //    RetirementWithdrawalProcessor#process) already charged exactly this dollar
                //    amount against (effectiveOtherIncome + conversionAmount), whether funded from
                //    the year's cash surplus or left in extraPoolFundedTax below -- subtract the
                //    EXPLICIT figure directly (audit A4 / T2 review: replaces the old
                //    totalNeed<=0 "noSpendDraw" proxy, which mis-fires for GuardrailSpendingInput --
                //    see the interface javadoc). This call then contributes only the marginal tax
                //    caused by the forced RMD/spend draw on top of that base.
                // Otherwise (positive spend draw, no conversion, nothing pre-charged) this is the
                // year's first and only ordinary-tax computation, so the full bundle is correct as-is.
                if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
                    var baseTax = taxCalculator.computeDetailedTax(
                            conversionAmount.add(effectiveOtherIncome), year, filingStatus);
                    withdrawalTax = detailed.totalTax().subtract(baseTax.totalTax()).max(BigDecimal.ZERO);
                } else if (alreadyChargedBaseTax.compareTo(BigDecimal.ZERO) > 0) {
                    withdrawalTax = detailed.totalTax().subtract(alreadyChargedBaseTax).max(BigDecimal.ZERO);
                } else {
                    withdrawalTax = detailed.totalTax();
                }

                lastTaxBreakdown = Optional.of(detailed);
            }

            // Long-term capital-gains tax on the realized FIFO gain + this year's qualified dividend,
            // stacked on ordinary income against the 0/15/20 LTCG brackets (+ deflated NIIT). This runs
            // only in retirement (executeWithdrawals is only called when retired). LTCG is a federal
            // tax, so it belongs in the federal-tax breakdown -- it folds into taxLiability and drains
            // the pools via the same cascade as the ordinary withdrawal tax, AND is returned separately
            // (below) so the engine can fold it into the year's federalTax field. It is deliberately NOT
            // added to lastTaxBreakdown here: for retired years RetirementTaxAnnotator recomputes (and
            // overwrites) the DTO's federal/state breakdown from scratch downstream of this call, so
            // that is where the fold actually has to happen -- see RetirementTaxAnnotator#annotate.
            BigDecimal ltcgTax = computeLtcgTax(realizedGain, taxableIncome, year, detailed);

            // Realized LTCG + qualified-dividend INCOME entering AGI-ex-SS for the Social Security
            // provisional-income convergence (audit B2), floored at zero (a net realized loss's
            // small AGI offset is out of scope for this model).
            BigDecimal realizedLtcgIncome = realizedGain.add(qualifiedDividendIncome).max(BigDecimal.ZERO);

            // A4: fold in any additional tax obligation the caller couldn't fund from this year's
            // cash surplus (or, for self-employment tax, had no other funding path at all) so it
            // drains the SAME pool cascade as the rest of this year's tax instead of vanishing --
            // see the interface javadoc for what extraPoolFundedTax represents.
            BigDecimal totalWithdrawalTax = withdrawalTax.add(ltcgTax).add(extraPoolFundedTax.max(BigDecimal.ZERO));
            TaxSourceResult withdrawalTaxSource = totalWithdrawalTax.compareTo(BigDecimal.ZERO) > 0
                    ? deductFromPools(totalWithdrawalTax) : TaxSourceResult.ZERO;

            return new WithdrawalTaxResult(
                    fromTaxable.add(fromTraditional).add(fromRoth), totalWithdrawalTax,
                    fromTaxable, traditionalOrdinaryIncome, fromRoth, withdrawalTaxSource, ltcgTax,
                    realizedLtcgIncome);
        }

        /**
         * Snapshot of MultiPool's mutable state: a deep copy of the taxable FIFO lots plus the
         * scalar traditional/roth balances, this year's booked qualified dividend, and the last tax
         * breakdown. Restoring returns the pool to its exact post-growth, pre-withdrawal state.
         */
        record MultiPoolMemento(java.util.List<BigDecimal[]> lots, BigDecimal traditional,
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
                                           CombinedTaxResult ordinaryTaxDetail) {
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
            return capitalGainsTaxCalculator.computeLtcgTax(ordinaryForLtcg, ltcgIncome, year,
                    filingStatus, year - baseYear, inflationRate, magi);
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
                                                       BigDecimal rmdAmount) {
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
            return executeConversionWithAmount(effectiveLimit, year, effectiveOtherIncome);
        }

        @Override
        public ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                                BigDecimal overrideAmount, BigDecimal rmdAmount) {
            // overrideAmount is an explicit, optimizer-scheduled dollar figure -- like the pre-existing
            // bracket-headroom check it bypasses, it does not respect RMD-consumed headroom either.
            if (overrideAmount.compareTo(BigDecimal.ZERO) <= 0
                    || traditional.compareTo(BigDecimal.ZERO) <= 0) {
                return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }
            return executeConversionWithAmount(overrideAmount, year, effectiveOtherIncome);
        }

        /**
         * Executes a Roth conversion of the given amount: transfers from traditional to Roth,
         * computes tax on the conversion, and deducts tax from pools. Shared by both
         * executeRothConversion (bracket/fixed amount) and executeRothConversionOverride
         * (optimizer-scheduled amount).
         */
        private ConversionResult executeConversionWithAmount(BigDecimal conversionLimit, int year,
                                                               BigDecimal effectiveOtherIncome) {
            BigDecimal actual = conversionLimit.min(traditional);
            traditional = traditional.subtract(actual);
            roth = roth.add(actual);

            if (taxCalculator != null) {
                BigDecimal taxableIncome = actual.add(effectiveOtherIncome);
                var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus);
                BigDecimal tax = detailed.totalTax();
                lastTaxBreakdown = Optional.of(detailed);
                TaxSourceResult taxSource = deductFromPools(tax);
                return new ConversionResult(actual, tax, taxSource);
            }
            return new ConversionResult(actual, BigDecimal.ZERO, TaxSourceResult.ZERO);
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
                    ctx.rmdAmount(), ctx.ltcgTax());

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
