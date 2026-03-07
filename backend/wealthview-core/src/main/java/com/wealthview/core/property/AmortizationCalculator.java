package com.wealthview.core.property;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class AmortizationCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    static final int MONTHS_PER_YEAR = 12;
    private static final BigDecimal MONTHLY_RATE_DIVISOR = new BigDecimal(MONTHS_PER_YEAR * 100);

    private AmortizationCalculator() {
    }

    /**
     * Computes remaining mortgage balance using standard amortization formula:
     * B = P * [(1+r)^n - (1+r)^p] / [(1+r)^n - 1]
     *
     * @param loanAmount principal
     * @param annualRate annual interest rate as percentage (e.g., 6.5 for 6.5%)
     * @param termMonths total loan term in months
     * @param startDate  loan start date
     * @param asOfDate   date to compute balance for
     * @return remaining balance, never negative
     */
    public static BigDecimal remainingBalance(BigDecimal loanAmount, BigDecimal annualRate,
                                               int termMonths, LocalDate startDate, LocalDate asOfDate) {
        long monthsBetween = ChronoUnit.MONTHS.between(startDate, asOfDate);

        if (monthsBetween <= 0) {
            return loanAmount;
        }

        int paymentsMade = (int) Math.min(monthsBetween, termMonths);

        if (paymentsMade >= termMonths) {
            return BigDecimal.ZERO;
        }

        if (annualRate.compareTo(BigDecimal.ZERO) == 0) {
            var monthlyPrincipal = loanAmount.divide(new BigDecimal(termMonths), MC);
            var remaining = loanAmount.subtract(monthlyPrincipal.multiply(new BigDecimal(paymentsMade)));
            return remaining.max(BigDecimal.ZERO);
        }

        var monthlyRate = annualRate.divide(MONTHLY_RATE_DIVISOR, MC);
        var onePlusR = BigDecimal.ONE.add(monthlyRate);

        var onePlusRtoN = pow(onePlusR, termMonths);
        var onePlusRtoP = pow(onePlusR, paymentsMade);

        // B = P * [(1+r)^n - (1+r)^p] / [(1+r)^n - 1]
        var numerator = onePlusRtoN.subtract(onePlusRtoP);
        var denominator = onePlusRtoN.subtract(BigDecimal.ONE);

        return loanAmount.multiply(numerator, MC)
                .divide(denominator, MC)
                .setScale(4, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO);
    }

    private static BigDecimal pow(BigDecimal base, int exponent) {
        var result = BigDecimal.ONE;
        for (int i = 0; i < exponent; i++) {
            result = result.multiply(base, MC);
        }
        return result;
    }
}
