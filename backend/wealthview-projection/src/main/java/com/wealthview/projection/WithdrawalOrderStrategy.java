package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.wealthview.core.projection.dto.PoolType;
import com.wealthview.core.projection.strategy.WithdrawalOrder;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.TaxCalculationStrategy;

/**
 * Allocates a retirement withdrawal across the taxable / traditional / roth sub-pools of a
 * {@link PoolStrategy.MultiPool}. Each {@link WithdrawalOrder} maps to one implementation.
 *
 * <p>Extracted from {@code PoolStrategy.MultiPool} during the Phase 3 decomposition; the
 * allocation arithmetic is unchanged.
 */
sealed interface WithdrawalOrderStrategy
        permits WithdrawalOrderStrategy.DynamicSequencingOrder,
                WithdrawalOrderStrategy.ProRataOrder,
                WithdrawalOrderStrategy.OrderedWithdrawalOrder {

    int SCALE = 4;
    RoundingMode ROUNDING = RoundingMode.HALF_UP;


    /** The amount drawn from each sub-pool for a single withdrawal. */
    record Result(BigDecimal fromTaxable, BigDecimal fromTraditional, BigDecimal fromRoth) {}

    /**
     * Per-withdrawal context (income, conversion, RMD, age and year) consumed by the
     * dynamic-sequencing order. Collapses what would otherwise be a long parameter list.
     */
    record WithdrawalContext(BigDecimal effectiveOtherIncome, BigDecimal conversionAmount,
                             BigDecimal rmdAmount, int age, int year) {}

    /**
     * Returns the allocation of a withdrawal across pools, or {@code null} if the total
     * balance is zero and the caller should return an empty result (PRO_RATA case only).
     */
    Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth);

    /**
     * Resolves the strategy for the given withdrawal order, binding the per-withdrawal
     * context needed by dynamic sequencing.
     */
    static WithdrawalOrderStrategy forOrder(WithdrawalOrder order, BigDecimal dynamicSequencingBracketRate,
                                            TaxCalculationStrategy taxCalculator, FilingStatus filingStatus,
                                            WithdrawalContext context) {
        return switch (order) {
            case DYNAMIC_SEQUENCING -> new DynamicSequencingOrder(
                    dynamicSequencingBracketRate, taxCalculator, filingStatus, order, context);
            case PRO_RATA -> new ProRataOrder();
            default -> new OrderedWithdrawalOrder(order);
        };
    }

    /** Draws from traditional up to the target bracket ceiling, then taxable, then roth. */
    final class DynamicSequencingOrder implements WithdrawalOrderStrategy {
        private final BigDecimal bracketRate;
        private final TaxCalculationStrategy taxCalc;
        private final FilingStatus filing;
        private final WithdrawalOrder fallbackOrder;
        private final WithdrawalContext context;

        DynamicSequencingOrder(BigDecimal bracketRate, TaxCalculationStrategy taxCalc,
                               FilingStatus filing, WithdrawalOrder fallbackOrder,
                               WithdrawalContext context) {
            this.bracketRate = bracketRate;
            this.taxCalc = taxCalc;
            this.filing = filing;
            this.fallbackOrder = fallbackOrder;
            this.context = context;
        }

        @Override
        public Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
            if (context.age() < RetirementAges.EARLY_WITHDRAWAL_AGE) {
                // Before 59.5 (using 60 as proxy): taxable only to avoid early withdrawal penalties
                return new Result(need.min(taxable), BigDecimal.ZERO, BigDecimal.ZERO);
            } else if (bracketRate != null && taxCalc != null) {
                BigDecimal bracketCeiling = taxCalc.computeMaxIncomeForTargetRate(
                        bracketRate, context.year(), filing);
                BigDecimal bracketSpace = bracketCeiling.subtract(context.effectiveOtherIncome())
                        .subtract(context.conversionAmount()).subtract(context.rmdAmount())
                        .max(BigDecimal.ZERO);
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

    /** Draws proportionally to each sub-pool's share of the total balance. */
    final class ProRataOrder implements WithdrawalOrderStrategy {
        @Override
        public Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
            BigDecimal total = taxable.add(traditional).add(roth);
            if (total.compareTo(BigDecimal.ZERO) <= 0) {
                return null; // signals caller to return empty result
            }
            BigDecimal capped = need.min(total);
            BigDecimal fromTaxable = capped.multiply(taxable).divide(total, SCALE, ROUNDING).min(taxable);
            BigDecimal fromTraditional = capped.multiply(traditional)
                    .divide(total, SCALE, ROUNDING).min(traditional);
            BigDecimal fromRoth = capped.subtract(fromTaxable).subtract(fromTraditional)
                    .min(roth).max(BigDecimal.ZERO);
            return new Result(fromTaxable, fromTraditional, fromRoth);
        }
    }

    /**
     * Draws from the sub-pools in the fixed priority order {@link WithdrawalOrder#drawSequence()}
     * defines (taxable / traditional / roth first).
     */
    final class OrderedWithdrawalOrder implements WithdrawalOrderStrategy {
        private final WithdrawalOrder order;

        OrderedWithdrawalOrder(WithdrawalOrder order) {
            this.order = order;
        }

        // ExhaustiveSwitchHasDefault: the switch below is exhaustive over PoolType, but
        // Checkstyle's MissingSwitchDefault rule (also an enforced gate) requires the default
        // anyway on a switch STATEMENT -- this suppression resolves that conflict between gates.
        @SuppressWarnings("PMD.ExhaustiveSwitchHasDefault")
        @Override
        public Result execute(BigDecimal need, BigDecimal taxable, BigDecimal traditional, BigDecimal roth) {
            BigDecimal remaining = need;
            BigDecimal fromTaxable = BigDecimal.ZERO;
            BigDecimal fromTraditional = BigDecimal.ZERO;
            BigDecimal fromRoth = BigDecimal.ZERO;

            for (PoolType pool : order.drawSequence()) {
                switch (pool) {
                    case TAXABLE -> {
                        fromTaxable = remaining.min(taxable);
                        remaining = remaining.subtract(fromTaxable);
                    }
                    case TRADITIONAL -> {
                        fromTraditional = remaining.min(traditional);
                        remaining = remaining.subtract(fromTraditional);
                    }
                    case ROTH -> {
                        fromRoth = remaining.min(roth);
                        remaining = remaining.subtract(fromRoth);
                    }
                    // Unreachable: PoolType is a closed 3-value enum and every value is handled
                    // above; this branch exists only to satisfy Checkstyle's MissingSwitchDefault.
                    default -> throw new IllegalStateException("Unexpected pool type: " + pool);
                }
            }

            return new Result(fromTaxable, fromTraditional, fromRoth);
        }
    }
}
