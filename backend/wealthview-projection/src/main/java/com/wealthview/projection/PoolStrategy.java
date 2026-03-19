package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Strategy for managing investment pool balances during projection year-loop.
 * SinglePool manages a single aggregate balance; MultiPool manages traditional/roth/taxable.
 */
sealed interface PoolStrategy permits PoolStrategy.SinglePool, PoolStrategy.MultiPool {

    static final int SCALE = 4;
    static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    BigDecimal getTotal();

    BigDecimal getWeightedReturn();

    BigDecimal applyContributions();

    GrowthResult applyGrowth();

    WithdrawalTaxResult executeWithdrawals(BigDecimal need, int year, BigDecimal effectiveOtherIncome,
                                           BigDecimal conversionAmount);

    ConversionResult executeRothConversion(int year, BigDecimal effectiveOtherIncome);

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
                                                      BigDecimal conversionAmount) {
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
        private final FederalTaxCalculator taxCalculator;

        MultiPool(Map<String, List<ProjectionAccountInput>> grouped,
                  BigDecimal weightedReturn,
                  FilingStatus filingStatus,
                  BigDecimal otherIncome,
                  BigDecimal annualRothConversion,
                  String rothConversionStrategy,
                  BigDecimal targetBracketRate,
                  Integer rothConversionStartYear,
                  WithdrawalOrder withdrawalOrder,
                  FederalTaxCalculator taxCalculator) {
            this.taxable = sumBalances(grouped.getOrDefault("taxable", List.of()));
            this.traditional = sumBalances(grouped.getOrDefault("traditional", List.of()));
            this.roth = sumBalances(grouped.getOrDefault("roth", List.of()));

            this.tradContrib = sumContribs(grouped.getOrDefault("traditional", List.of()));
            this.rothContrib = sumContribs(grouped.getOrDefault("roth", List.of()));
            this.taxableContrib = sumContribs(grouped.getOrDefault("taxable", List.of()));

            this.weightedReturn = weightedReturn;
            this.filingStatus = filingStatus;
            this.otherIncome = otherIncome;
            this.annualRothConversion = annualRothConversion;
            this.rothConversionStrategy = rothConversionStrategy;
            this.targetBracketRate = targetBracketRate;
            this.rothConversionStartYear = rothConversionStartYear;
            this.withdrawalOrder = withdrawalOrder;
            this.taxCalculator = taxCalculator;
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
                                                      BigDecimal conversionAmount) {
            if (totalNeed.compareTo(BigDecimal.ZERO) <= 0) {
                return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
            }

            BigDecimal fromTaxable;
            BigDecimal fromTraditional;
            BigDecimal fromRoth;

            if (withdrawalOrder == WithdrawalOrder.PRO_RATA) {
                BigDecimal total = getTotal();
                if (total.compareTo(BigDecimal.ZERO) <= 0) {
                    return new WithdrawalTaxResult(BigDecimal.ZERO, BigDecimal.ZERO,
                            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, TaxSourceResult.ZERO);
                }
                BigDecimal need = totalNeed.min(total);
                fromTaxable = need.multiply(taxable).divide(total, SCALE, ROUNDING).min(taxable);
                fromTraditional = need.multiply(traditional).divide(total, SCALE, ROUNDING).min(traditional);
                fromRoth = need.subtract(fromTaxable).subtract(fromTraditional).min(roth).max(BigDecimal.ZERO);
            } else {
                var ordered = executeOrderedWithdrawals(totalNeed);
                fromTaxable = ordered[0];
                fromTraditional = ordered[1];
                fromRoth = ordered[2];
            }

            taxable = taxable.subtract(fromTaxable);
            traditional = traditional.subtract(fromTraditional);
            roth = roth.subtract(fromRoth);

            TaxSourceResult withdrawalTaxSource = TaxSourceResult.ZERO;
            BigDecimal withdrawalTax = BigDecimal.ZERO;
            if (fromTraditional.compareTo(BigDecimal.ZERO) > 0 && taxCalculator != null) {
                withdrawalTax = taxCalculator.computeTax(
                        fromTraditional.add(effectiveOtherIncome), year, filingStatus);
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
                BigDecimal bracketCeiling = taxCalculator.computeMaxIncomeForBracket(
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
            BigDecimal actual = effectiveLimit.min(traditional);
            traditional = traditional.subtract(actual);
            roth = roth.add(actual);

            if (taxCalculator != null) {
                BigDecimal taxableIncome = actual.add(effectiveOtherIncome);
                BigDecimal tax = taxCalculator.computeTax(taxableIncome, year, filingStatus);
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
            return new ProjectionYearDto(
                    year, age, startBalance, contributions, totalGrowth, withdrawals, getTotal(), retired,
                    traditional, roth, taxable,
                    conversionAmount.compareTo(BigDecimal.ZERO) > 0 ? conversionAmount : null,
                    taxLiability.compareTo(BigDecimal.ZERO) > 0 ? taxLiability : null,
                    null, null, null, null, null, null,
                    null, null, null, null, null, null, null, null,
                    null, null, null,
                    growthResult.taxable(), growthResult.traditional(), growthResult.roth(),
                    combinedTaxSource.fromTaxable().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromTaxable() : null,
                    combinedTaxSource.fromTraditional().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromTraditional() : null,
                    combinedTaxSource.fromRoth().compareTo(BigDecimal.ZERO) > 0 ? combinedTaxSource.fromRoth() : null,
                    withdrawalFromTaxable.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromTaxable : null,
                    withdrawalFromTraditional.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromTraditional : null,
                    withdrawalFromRoth.compareTo(BigDecimal.ZERO) > 0 ? withdrawalFromRoth : null,
                    null);
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
