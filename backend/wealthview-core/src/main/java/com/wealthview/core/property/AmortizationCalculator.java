package com.wealthview.core.property;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.wealthview.core.common.Money;

public final class AmortizationCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    static final int MONTHS_PER_YEAR = 12;
    private static final BigDecimal MONTHLY_RATE_DIVISOR = new BigDecimal(MONTHS_PER_YEAR);

    private AmortizationCalculator() {
    }

    /**
     * Computes remaining mortgage balance using standard amortization formula
     * {@code B = P * [(1+r)^n - (1+r)^p] / [(1+r)^n - 1]}.
     *
     * @param loanAmount principal
     * @param annualRate annual interest rate as decimal (e.g., 0.065 for 6.5%)
     * @param termMonths total loan term in months
     * @param startDate  loan start date
     * @param asOfDate   date to compute balance for
     * @return remaining balance, never negative
     */
    public static BigDecimal remainingBalance(BigDecimal loanAmount, BigDecimal annualRate,
                                               int termMonths, LocalDate startDate, LocalDate asOfDate) {
        if (termMonths <= 0) {
            // A stored non-positive term has no amortisation schedule; treat the loan as fully repaid
            // rather than dividing by zero.
            return BigDecimal.ZERO;
        }

        long monthsBetween = ChronoUnit.MONTHS.between(startDate, asOfDate);

        if (monthsBetween <= 0) {
            return loanAmount;
        }

        return balanceAfterPayments(loanAmount, annualRate, termMonths, (int) Math.min(monthsBetween, termMonths));
    }

    /**
     * The interest/principal split of payments {@code firstPayment..lastPayment} (1-based,
     * inclusive), clamped to the loan's term. Each payment's interest is the monthly rate times
     * the balance outstanding before it (the same closed-form balance as
     * {@link #remainingBalance}); its principal is the rest of the fixed monthly payment.
     *
     * @return the split, or {@link DebtService#NONE} when no payment falls in the range
     */
    public static DebtService debtServiceForPayments(BigDecimal loanAmount, BigDecimal annualRate,
                                                     int termMonths, int firstPayment, int lastPayment) {
        int first = Math.max(1, firstPayment);
        int last = Math.min(termMonths, lastPayment);
        if (termMonths <= 0 || last < first) {
            return DebtService.NONE;
        }

        var total = monthlyPayment(loanAmount, annualRate, termMonths).multiply(new BigDecimal(last - first + 1));
        var interest = annualRate.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : Money.scale(scheduledInterest(loanAmount, annualRate, termMonths, first, last));
        return new DebtService(interest, total.subtract(interest).max(BigDecimal.ZERO));
    }

    /**
     * Sum of {@code r * B(k-1)} for payments {@code first..last} at a non-zero rate. Equal to calling
     * {@link #balanceAfterPayments} per payment, but advances {@code (1+r)^p} one step at a time
     * instead of recomputing both powers for every payment.
     */
    private static BigDecimal scheduledInterest(BigDecimal loanAmount, BigDecimal annualRate,
                                                int termMonths, int first, int last) {
        var monthlyRate = annualRate.divide(MONTHLY_RATE_DIVISOR, MC);
        var onePlusR = BigDecimal.ONE.add(monthlyRate);
        var onePlusRtoN = pow(onePlusR, termMonths);
        var denominator = onePlusRtoN.subtract(BigDecimal.ONE);
        var onePlusRtoP = pow(onePlusR, first - 1);

        var interest = BigDecimal.ZERO;
        for (int k = first; k <= last; k++) {
            var openingBalance = loanAmount.multiply(onePlusRtoN.subtract(onePlusRtoP), MC)
                    .divide(denominator, MC)
                    .setScale(Money.SCALE, Money.ROUNDING);
            interest = interest.add(monthlyRate.multiply(openingBalance, MC));
            onePlusRtoP = onePlusRtoP.multiply(onePlusR, MC);
        }
        return interest;
    }

    /**
     * The interest/principal split of the payments that fall in {@code calendarYear}. Payment
     * {@code k} falls {@code k} months after the loan's start month (the same timing
     * {@link #remainingBalance} uses), so the start year and the payoff year count only the
     * payments made in them, and years before the first payment or after payoff are
     * {@link DebtService#NONE}.
     */
    public static DebtService debtServiceForYear(BigDecimal loanAmount, BigDecimal annualRate,
                                                 int termMonths, LocalDate startDate, int calendarYear) {
        int startMonthIndex = startDate.getYear() * MONTHS_PER_YEAR + startDate.getMonthValue() - 1;
        int yearFirstMonthIndex = calendarYear * MONTHS_PER_YEAR;
        return debtServiceForPayments(loanAmount, annualRate, termMonths,
                yearFirstMonthIndex - startMonthIndex,
                yearFirstMonthIndex + MONTHS_PER_YEAR - 1 - startMonthIndex);
    }

    /** {@code B = P * [(1+r)^n - (1+r)^p] / [(1+r)^n - 1]} after {@code paymentsMade} payments. */
    private static BigDecimal balanceAfterPayments(BigDecimal loanAmount, BigDecimal annualRate,
                                                   int termMonths, int paymentsMade) {
        if (paymentsMade <= 0) {
            return loanAmount;
        }

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
                .setScale(Money.SCALE, Money.ROUNDING)
                .max(BigDecimal.ZERO);
    }

    /**
     * Computes monthly mortgage payment using the standard amortization formula
     * {@code M = P * [r(1+r)^n / ((1+r)^n - 1)]}.
     *
     * @param loanAmount principal
     * @param annualRate annual interest rate as decimal (e.g., 0.065 for 6.5%)
     * @param termMonths total loan term in months
     * @return monthly payment, or null if any input is null; zero for a non-positive term
     */
    public static BigDecimal monthlyPayment(BigDecimal loanAmount, BigDecimal annualRate, int termMonths) {
        if (loanAmount == null || annualRate == null) {
            return null;
        }

        if (termMonths <= 0) {
            return BigDecimal.ZERO.setScale(Money.SCALE, Money.ROUNDING);
        }

        if (annualRate.compareTo(BigDecimal.ZERO) == 0) {
            return loanAmount.divide(new BigDecimal(termMonths), 4, RoundingMode.HALF_UP);
        }

        var monthlyRate = annualRate.divide(MONTHLY_RATE_DIVISOR, MC);
        var onePlusR = BigDecimal.ONE.add(monthlyRate);
        var onePlusRtoN = pow(onePlusR, termMonths);

        // M = P * [r(1+r)^n / ((1+r)^n - 1)]
        var numerator = monthlyRate.multiply(onePlusRtoN, MC);
        var denominator = onePlusRtoN.subtract(BigDecimal.ONE);

        return loanAmount.multiply(numerator, MC)
                .divide(denominator, MC)
                .setScale(Money.SCALE, Money.ROUNDING);
    }

    private static BigDecimal pow(BigDecimal base, int exponent) {
        var result = BigDecimal.ONE;
        for (int i = 0; i < exponent; i++) {
            result = result.multiply(base, MC);
        }
        return result;
    }
}
