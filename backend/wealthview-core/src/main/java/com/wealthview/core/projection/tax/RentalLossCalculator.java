package com.wealthview.core.projection.tax;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Applies IRS passive activity loss rules to rental income.
 * - Active (REPS/STR): all losses offset any income type.
 * - Passive: losses offset passive income only, with $25k exception for MAGI < $150k.
 */
@Component
public class RentalLossCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;
    private static final BigDecimal EXCEPTION_AMOUNT = new BigDecimal("25000");
    private static final BigDecimal PHASEOUT_START = new BigDecimal("100000");
    private static final BigDecimal PHASEOUT_END = new BigDecimal("150000");
    private static final BigDecimal PHASEOUT_RATE = new BigDecimal("0.5");

    public record LossResult(
            BigDecimal lossAppliedToIncome,
            BigDecimal lossSuspended,
            BigDecimal suspendedLossReleased,
            BigDecimal netTaxableIncome
    ) {}

    /**
     * @param netRentalIncome    gross rent minus expenses minus depreciation (can be negative = loss)
     * @param taxTreatment       rental_passive, rental_active_reps, or rental_active_str
     * @param otherPassiveIncome other passive income available to offset losses
     * @param magi               modified adjusted gross income (for $25k exception phaseout)
     * @param priorSuspended     cumulative suspended passive losses from prior years
     */
    public LossResult applyLossRules(BigDecimal netRentalIncome, String taxTreatment,
                                      BigDecimal otherPassiveIncome, BigDecimal magi,
                                      BigDecimal priorSuspended) {
        // Positive income: release prior suspended losses against it
        if (netRentalIncome.compareTo(BigDecimal.ZERO) >= 0) {
            var released = priorSuspended.min(netRentalIncome);
            var netTaxable = netRentalIncome.subtract(released);
            return new LossResult(BigDecimal.ZERO, priorSuspended.subtract(released), released, netTaxable);
        }

        // We have a loss (negative income)
        var loss = netRentalIncome.abs();

        // Active: all losses offset any income
        if ("rental_active_reps".equals(taxTreatment) || "rental_active_str".equals(taxTreatment)) {
            return new LossResult(loss, BigDecimal.ZERO, BigDecimal.ZERO, netRentalIncome);
        }

        // Passive: compute how much can be applied
        var appliedViaPassive = loss.min(otherPassiveIncome);
        var remainingLoss = loss.subtract(appliedViaPassive);

        // $25k exception with MAGI phaseout
        var exception = computeException(magi);
        var appliedViaException = remainingLoss.min(exception);
        var totalApplied = appliedViaPassive.add(appliedViaException);

        var suspended = loss.subtract(totalApplied).add(priorSuspended);
        var netTaxable = totalApplied.negate();

        return new LossResult(totalApplied, suspended, BigDecimal.ZERO, netTaxable);
    }

    private BigDecimal computeException(BigDecimal magi) {
        if (magi.compareTo(PHASEOUT_START) <= 0) {
            return EXCEPTION_AMOUNT;
        }
        if (magi.compareTo(PHASEOUT_END) >= 0) {
            return BigDecimal.ZERO;
        }
        var reduction = magi.subtract(PHASEOUT_START).multiply(PHASEOUT_RATE).setScale(SCALE, ROUNDING);
        return EXCEPTION_AMOUNT.subtract(reduction).max(BigDecimal.ZERO);
    }
}
