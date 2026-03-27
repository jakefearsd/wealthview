package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.CombinedTaxResult;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

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

    BigDecimal getWeightedReturn();

    BigDecimal applyContributions();

    GrowthResult applyGrowth();

    WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount, BigDecimal rmdAmount, int age);

    ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome);

    default ConversionResult executeRothConversionOverride(int year, BigDecimal effectiveOtherIncome,
                                                            BigDecimal overrideAmount) {
        return executeRothConversion(year, effectiveOtherIncome);
    }

    void floorAtZero();

    /**
     * Deposits a surplus amount into the taxable account (or aggregate balance for SinglePool).
     */
    void depositToTaxable(BigDecimal amount);

    ProjectionYearDto buildYearDto(int year, int age, BigDecimal startBalance,
                                   BigDecimal contributions, BigDecimal totalGrowth,
                                   BigDecimal withdrawals, boolean retired,
                                   BigDecimal conversionAmount, BigDecimal taxLiability,
                                   GrowthResult growthResult,
                                   BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                                   BigDecimal withdrawalFromRoth,
                                   TaxSourceResult combinedTaxSource);

    /**
     * Returns the MAGI value to pass to processIncomeSources.
     */
    BigDecimal getMagi();

    /**
     * Returns the filing status string to pass to processIncomeSources.
     */
    String getFilingStatusString();

    /**
     * Whether income sources should be processed every year (true) or only when retired (false).
     */
    boolean processIncomeSourcesEveryYear();

    /**
     * Returns the accumulated tax breakdown from the most recent withdrawal + conversion cycle.
     * Only meaningful for MultiPool when a CombinedTaxCalculator is in use.
     */
    default CombinedTaxResult getLastTaxBreakdown() {
        return null;
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

    record WithdrawalTaxResult(BigDecimal totalWithdrawn, BigDecimal taxLiability,
                               BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth,
                               TaxSourceResult taxSource) {}

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
            BigDecimal dynamicSequencingBracketRate) {}

    /**
     * Factory method that decides whether to create a SinglePool or MultiPool based on the
     * account types present, and encapsulates all construction details.
     */
    static PoolStrategy create(List<ProjectionAccountInput> accounts, PoolConfig config) {
        if (hasMultipleAccountTypes(accounts)) {
            Map<String, List<ProjectionAccountInput>> grouped = accounts.stream()
                    .collect(Collectors.groupingBy(ProjectionAccountInput::accountType));

            BigDecimal totalBalance = sumInitialBalances(grouped.getOrDefault(POOL_TAXABLE, List.of()))
                    .add(sumInitialBalances(grouped.getOrDefault(POOL_TRADITIONAL, List.of())))
                    .add(sumInitialBalances(grouped.getOrDefault(POOL_ROTH, List.of())));

            return new MultiPool(grouped,
                    computeWeightedReturn(accounts, totalBalance),
                    config.filingStatus(), config.otherIncome(), config.annualRothConversion(),
                    config.rothConversionStrategy(), config.targetBracketRate(),
                    config.rothConversionStartYear(), config.withdrawalOrder(), config.taxCalculator(),
                    config.dynamicSequencingBracketRate());
        } else {
            BigDecimal balance = sumInitialBalances(accounts);
            return new SinglePool(balance, sumContributions(accounts),
                    computeWeightedReturn(accounts, balance));
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

    private static BigDecimal computeWeightedReturn(List<ProjectionAccountInput> accounts,
                                                     BigDecimal totalBalance) {
        if (totalBalance.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal weightedSum = BigDecimal.ZERO;
        for (var account : accounts) {
            weightedSum = weightedSum.add(
                    account.initialBalance().multiply(account.expectedReturn()));
        }
        return weightedSum.divide(totalBalance, SCALE + 4, ROUNDING);
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
                                                      BigDecimal rmdAmount, int age) {
            // Simple path: withdrawal is just min(need, balance), no tax tracking
            BigDecimal withdrawn = need.min(balance);
            balance = balance.subtract(withdrawn);
            return new WithdrawalTaxResult(withdrawn, BigDecimal.ZERO,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }

        @Override
        public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome) {
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
        public ProjectionYearDto buildYearDto(int year, int age, BigDecimal startBalance,
                                              BigDecimal contributions, BigDecimal totalGrowth,
                                              BigDecimal withdrawals, boolean retired,
                                              BigDecimal conversionAmount, BigDecimal taxLiability,
                                              GrowthResult growthResult,
                                              BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                                              BigDecimal withdrawalFromRoth,
                                              TaxSourceResult combinedTaxSource) {
            return ProjectionYearDto.simple(year, age, startBalance, contributions,
                    totalGrowth, withdrawals, balance, retired);
        }

        @Override
        public BigDecimal getMagi() {
            return BigDecimal.ZERO;
        }

        @Override
        public String getFilingStatusString() {
            return "single";
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
        private BigDecimal taxable;
        private BigDecimal traditional;
        private BigDecimal roth;
        private CombinedTaxResult lastTaxBreakdown;

        private final BigDecimal tradContrib;
        private final BigDecimal rothContrib;
        private final BigDecimal taxableContrib;
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

        private static final int EARLY_WITHDRAWAL_AGE = 60; // proxy for 59.5

        MultiPool(Map<String, List<ProjectionAccountInput>> grouped,
                  BigDecimal weightedReturn,
                  FilingStatus filingStatus,
                  BigDecimal otherIncome,
                  BigDecimal annualRothConversion,
                  String rothConversionStrategy,
                  BigDecimal targetBracketRate,
                  Integer rothConversionStartYear,
                  WithdrawalOrder withdrawalOrder,
                  TaxCalculationStrategy taxCalculator,
                  BigDecimal dynamicSequencingBracketRate) {
            this.taxable = sumBalances(grouped.getOrDefault(POOL_TAXABLE, List.of()));
            this.traditional = sumBalances(grouped.getOrDefault(POOL_TRADITIONAL, List.of()));
            this.roth = sumBalances(grouped.getOrDefault(POOL_ROTH, List.of()));

            this.tradContrib = sumContribs(grouped.getOrDefault(POOL_TRADITIONAL, List.of()));
            this.rothContrib = sumContribs(grouped.getOrDefault(POOL_ROTH, List.of()));
            this.taxableContrib = sumContribs(grouped.getOrDefault(POOL_TAXABLE, List.of()));

            this.weightedReturn = weightedReturn;
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
            return taxable.add(traditional).add(roth);
        }

        @Override
        public BigDecimal getWeightedReturn() {
            return weightedReturn;
        }

        @Override
        public BigDecimal applyContributions() {
            traditional = traditional.add(tradContrib);
            roth = roth.add(rothContrib);
            taxable = taxable.add(taxableContrib);
            return tradContrib.add(rothContrib).add(taxableContrib);
        }

        @Override
        public GrowthResult applyGrowth() {
            BigDecimal tradGrowth = traditional.multiply(weightedReturn).setScale(SCALE, ROUNDING);
            BigDecimal rothGrowth = roth.multiply(weightedReturn).setScale(SCALE, ROUNDING);
            BigDecimal taxableGrowth = taxable.multiply(weightedReturn).setScale(SCALE, ROUNDING);
            traditional = traditional.add(tradGrowth);
            roth = roth.add(rothGrowth);
            taxable = taxable.add(taxableGrowth);
            return new GrowthResult(tradGrowth.add(rothGrowth).add(taxableGrowth),
                    taxableGrowth, tradGrowth, rothGrowth);
        }

        @Override
        public WithdrawalTaxResult executeWithdrawals(BigDecimal totalNeed, int year,
                                                      BigDecimal effectiveOtherIncome,
                                                      BigDecimal conversionAmount,
                                                      BigDecimal rmdAmount, int age) {
            if (totalNeed.compareTo(BigDecimal.ZERO) <= 0) {
                return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }

            WithdrawalOrderStrategy strategy = switch (withdrawalOrder) {
                case DYNAMIC_SEQUENCING -> new DynamicSequencingOrder(
                        dynamicSequencingBracketRate, taxCalculator, filingStatus,
                        effectiveOtherIncome, conversionAmount, rmdAmount, age, year,
                        withdrawalOrder);
                case PRO_RATA -> new ProRataOrder();
                default -> new OrderedWithdrawalOrder(withdrawalOrder);
            };

            WithdrawalOrderStrategy.Result allocation = strategy.execute(totalNeed, taxable, traditional, roth);
            if (allocation == null) {
                return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }

            BigDecimal fromTaxable = allocation.fromTaxable();
            BigDecimal fromTraditional = allocation.fromTraditional();
            BigDecimal fromRoth = allocation.fromRoth();

            taxable = taxable.subtract(fromTaxable);
            traditional = traditional.subtract(fromTraditional);
            roth = roth.subtract(fromRoth);

            TaxSourceResult withdrawalTaxSource = TaxSourceResult.ZERO;
            BigDecimal withdrawalTax = BigDecimal.ZERO;
            BigDecimal taxableIncome = fromTraditional.add(effectiveOtherIncome).add(conversionAmount);
            if (taxableIncome.compareTo(BigDecimal.ZERO) > 0 && taxCalculator != null) {
                var detailed = taxCalculator.computeDetailedTax(taxableIncome, year, filingStatus);

                if (conversionAmount.compareTo(BigDecimal.ZERO) > 0) {
                    // Roth conversion tax was already computed on (conversionAmount + effectiveOtherIncome).
                    // Compute only the marginal tax caused by the traditional withdrawal to avoid
                    // double-counting the base income through separate progressive-bracket calculations.
                    var baseTax = taxCalculator.computeDetailedTax(
                            conversionAmount.add(effectiveOtherIncome), year, filingStatus);
                    withdrawalTax = detailed.totalTax().subtract(baseTax.totalTax()).max(BigDecimal.ZERO);
                } else {
                    withdrawalTax = detailed.totalTax();
                }

                lastTaxBreakdown = detailed;
                withdrawalTaxSource = deductFromPools(withdrawalTax);
            }

            return new WithdrawalTaxResult(
                    fromTaxable.add(fromTraditional).add(fromRoth), withdrawalTax,
                    fromTaxable, fromTraditional, fromRoth, withdrawalTaxSource);
        }

        @Override
        public ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome) {
            if (rothConversionStartYear != null && year < rothConversionStartYear) {
                return new ConversionResult(BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }

            BigDecimal effectiveLimit;
            if ("fill_bracket".equals(rothConversionStrategy) && targetBracketRate != null && taxCalculator != null) {
                BigDecimal bracketCeiling = taxCalculator.computeMaxIncomeForTargetRate(
                        targetBracketRate, year, filingStatus);
                BigDecimal space = bracketCeiling.subtract(effectiveOtherIncome).max(BigDecimal.ZERO);
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
                                                                BigDecimal overrideAmount) {
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
                lastTaxBreakdown = detailed;
                TaxSourceResult taxSource = deductFromPools(tax);
                return new ConversionResult(actual, tax, taxSource);
            }
            return new ConversionResult(actual, BigDecimal.ZERO, TaxSourceResult.ZERO);
        }

        @Override
        public void floorAtZero() {
            taxable = taxable.max(BigDecimal.ZERO);
            traditional = traditional.max(BigDecimal.ZERO);
            roth = roth.max(BigDecimal.ZERO);
        }

        @Override
        public CombinedTaxResult getLastTaxBreakdown() {
            return lastTaxBreakdown;
        }

        @Override
        public void depositToTaxable(BigDecimal amount) {
            taxable = taxable.add(amount);
        }

        @Override
        public ProjectionYearDto buildYearDto(int year, int age, BigDecimal startBalance,
                                              BigDecimal contributions, BigDecimal totalGrowth,
                                              BigDecimal withdrawals, boolean retired,
                                              BigDecimal conversionAmount, BigDecimal taxLiability,
                                              GrowthResult growthResult,
                                              BigDecimal withdrawalFromTaxable, BigDecimal withdrawalFromTraditional,
                                              BigDecimal withdrawalFromRoth,
                                              TaxSourceResult combinedTaxSource) {
            BigDecimal fedTax = null;
            BigDecimal stTax = null;
            BigDecimal saltDed = null;
            Boolean usedItemized = null;
            if (lastTaxBreakdown != null) {
                fedTax = lastTaxBreakdown.federalTax();
                stTax = lastTaxBreakdown.stateTax().compareTo(BigDecimal.ZERO) > 0
                        ? lastTaxBreakdown.stateTax() : null;
                saltDed = lastTaxBreakdown.saltDeduction().compareTo(BigDecimal.ZERO) > 0
                        ? lastTaxBreakdown.saltDeduction() : null;
                usedItemized = lastTaxBreakdown.usedItemized();
            }
            lastTaxBreakdown = null;

            return ProjectionYearDto.builder()
                    .year(year).age(age).startBalance(startBalance)
                    .contributions(contributions).growth(totalGrowth)
                    .withdrawals(withdrawals).endBalance(getTotal()).retired(retired)
                    .traditionalBalance(traditional).rothBalance(roth).taxableBalance(taxable)
                    .rothConversionAmount(conversionAmount.compareTo(BigDecimal.ZERO) > 0 ? conversionAmount : null)
                    .taxLiability(taxLiability.compareTo(BigDecimal.ZERO) > 0 ? taxLiability : null)
                    .taxableGrowth(growthResult.taxable())
                    .traditionalGrowth(growthResult.traditional())
                    .rothGrowth(growthResult.roth())
                    .taxPaidFromTaxable(combinedTaxSource.fromTaxable().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromTaxable() : null)
                    .taxPaidFromTraditional(combinedTaxSource.fromTraditional().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromTraditional() : null)
                    .taxPaidFromRoth(combinedTaxSource.fromRoth().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromRoth() : null)
                    .withdrawalFromTaxable(withdrawalFromTaxable.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromTaxable : null)
                    .withdrawalFromTraditional(withdrawalFromTraditional.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromTraditional : null)
                    .withdrawalFromRoth(withdrawalFromRoth.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromRoth : null)
                    .federalTax(fedTax).stateTax(stTax).saltDeduction(saltDed)
                    .usedItemizedDeduction(usedItemized)
                    .build();
        }

        @Override
        public BigDecimal getMagi() {
            return otherIncome;
        }

        @Override
        public String getFilingStatusString() {
            return filingStatus.name().toLowerCase(Locale.US);
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

        // --- WithdrawalOrderStrategy and implementations ---

        private sealed interface WithdrawalOrderStrategy
                permits MultiPool.DynamicSequencingOrder, MultiPool.ProRataOrder, MultiPool.OrderedWithdrawalOrder {
            record Result(BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth) {}

            /**
             * Returns the allocation of a withdrawal across pools, or null if the total balance is zero
             * and the caller should return an empty result (PRO_RATA case only).
             */
            Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth);
        }

        private final class DynamicSequencingOrder implements WithdrawalOrderStrategy {
            private final BigDecimal bracketRate;
            private final TaxCalculationStrategy taxCalc;
            private final FilingStatus filing;
            private final BigDecimal effectiveOtherIncome;
            private final BigDecimal conversionAmount;
            private final BigDecimal rmdAmount;
            private final int age;
            private final int year;
            private final WithdrawalOrder fallbackOrder;

            DynamicSequencingOrder(BigDecimal bracketRate, TaxCalculationStrategy taxCalc,
                                   FilingStatus filing, BigDecimal effectiveOtherIncome,
                                   BigDecimal conversionAmount, BigDecimal rmdAmount,
                                   int age, int year, WithdrawalOrder fallbackOrder) {
                this.bracketRate = bracketRate;
                this.taxCalc = taxCalc;
                this.filing = filing;
                this.effectiveOtherIncome = effectiveOtherIncome;
                this.conversionAmount = conversionAmount;
                this.rmdAmount = rmdAmount;
                this.age = age;
                this.year = year;
                this.fallbackOrder = fallbackOrder;
            }

            @Override
            public Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
                if (age < EARLY_WITHDRAWAL_AGE) {
                    // Before 59.5 (using 60 as proxy): taxable only to avoid early withdrawal penalties
                    return new Result(need.min(taxable), BigDecimal.ZERO, BigDecimal.ZERO);
                } else if (bracketRate != null && taxCalc != null) {
                    BigDecimal bracketCeiling = taxCalc.computeMaxIncomeForTargetRate(bracketRate, year, filing);
                    BigDecimal bracketSpace = bracketCeiling.subtract(effectiveOtherIncome)
                            .subtract(conversionAmount).subtract(rmdAmount).max(BigDecimal.ZERO);
                    BigDecimal fromTraditional = bracketSpace.min(traditional).min(need);
                    BigDecimal remaining = need.subtract(fromTraditional);
                    BigDecimal fromTaxable = remaining.min(taxable);
                    remaining = remaining.subtract(fromTaxable);
                    BigDecimal fromRoth = remaining.min(roth);
                    return new Result(fromTaxable, fromTraditional, fromRoth);
                } else {
                    // Fallback to taxable_first if no bracket rate configured
                    return new OrderedWithdrawalOrder(fallbackOrder).execute(need, taxable, traditional, roth);
                }
            }
        }

        private static final class ProRataOrder implements WithdrawalOrderStrategy {
            @Override
            public Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
                BigDecimal total = taxable.add(traditional).add(roth);
                if (total.compareTo(BigDecimal.ZERO) <= 0) {
                    return null; // signals caller to return empty result
                }
                BigDecimal capped = need.min(total);
                BigDecimal fromTaxable = capped.multiply(taxable).divide(total, SCALE, ROUNDING).min(taxable);
                BigDecimal fromTraditional = capped.multiply(traditional).divide(total, SCALE, ROUNDING).min(traditional);
                BigDecimal fromRoth = capped.subtract(fromTaxable).subtract(fromTraditional).min(roth).max(BigDecimal.ZERO);
                return new Result(fromTaxable, fromTraditional, fromRoth);
            }
        }

        private static final class OrderedWithdrawalOrder implements WithdrawalOrderStrategy {
            private final WithdrawalOrder order;

            OrderedWithdrawalOrder(WithdrawalOrder order) {
                this.order = order;
            }

            @Override
            public Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
                BigDecimal remaining = need;
                BigDecimal[] pools = switch (order) {
                    case TRADITIONAL_FIRST -> new BigDecimal[]{traditional, taxable, roth};
                    case ROTH_FIRST -> new BigDecimal[]{roth, taxable, traditional};
                    default -> new BigDecimal[]{taxable, traditional, roth};
                };

                BigDecimal[] drawn = new BigDecimal[3];
                for (int i = 0; i < 3; i++) {
                    drawn[i] = remaining.min(pools[i]);
                    remaining = remaining.subtract(drawn[i]);
                }

                return switch (order) {
                    case TRADITIONAL_FIRST -> new Result(drawn[1], drawn[0], drawn[2]);
                    case ROTH_FIRST -> new Result(drawn[1], drawn[2], drawn[0]);
                    default -> new Result(drawn[0], drawn[1], drawn[2]);
                };
            }
        }

        private BigDecimal[] executeOrderedWithdrawals(BigDecimal totalNeed) {
            BigDecimal remaining = totalNeed;
            BigDecimal[] pools = switch (withdrawalOrder) {
                case TRADITIONAL_FIRST -> new BigDecimal[]{traditional, taxable, roth};
                case ROTH_FIRST -> new BigDecimal[]{roth, taxable, traditional};
                default -> new BigDecimal[]{taxable, traditional, roth};
            };

            BigDecimal[] drawn = new BigDecimal[3];
            for (int i = 0; i < 3; i++) {
                drawn[i] = remaining.min(pools[i]);
                remaining = remaining.subtract(drawn[i]);
            }

            return switch (withdrawalOrder) {
                case TRADITIONAL_FIRST -> new BigDecimal[]{drawn[1], drawn[0], drawn[2]};
                case ROTH_FIRST -> new BigDecimal[]{drawn[1], drawn[2], drawn[0]};
                default -> new BigDecimal[]{drawn[0], drawn[1], drawn[2]};
            };
        }

        private TaxSourceResult deductFromPools(BigDecimal amount) {
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                return TaxSourceResult.ZERO;
            }
            BigDecimal remaining = amount;

            BigDecimal fromTax = remaining.min(taxable);
            taxable = taxable.subtract(fromTax);
            remaining = remaining.subtract(fromTax);

            BigDecimal fromTrad = remaining.min(traditional);
            traditional = traditional.subtract(fromTrad);
            remaining = remaining.subtract(fromTrad);

            roth = roth.subtract(remaining);
            return new TaxSourceResult(fromTax, fromTrad, remaining);
        }
    }
}
