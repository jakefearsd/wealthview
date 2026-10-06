package com.wealthview.core.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.wealthview.core.common.Money;
import com.wealthview.persistence.entity.PropertyEntity;

/**
 * The single home for property mortgage math shared by the dashboard, analytics,
 * ROI, and projection services. Two balance semantics exist and both are
 * intentional:
 *
 * <ul>
 *   <li>{@link #effectiveCurrentMortgageBalance(PropertyEntity)} — "what is the
 *       balance right now" as the user wants it reported: the amortization
 *       schedule only when they opted in via {@code useComputedBalance},
 *       otherwise their manually entered balance.</li>
 *   <li>{@link #mortgageBalanceAsOf(PropertyEntity, LocalDate)} — time-series
 *       semantics for history charts and future projections: amortize whenever
 *       loan details exist (a manual balance is a single point in time and
 *       cannot be projected), falling back to the manual balance without them.</li>
 * </ul>
 */
public final class PropertyFinance {

    private static final int MONTHS_PER_YEAR = 12;

    private PropertyFinance() {
    }

    public static BigDecimal effectiveCurrentMortgageBalance(PropertyEntity property) {
        if (property.isUseComputedBalance() && property.hasLoanDetails()) {
            return remainingBalance(property, LocalDate.now());
        }
        return property.getMortgageBalance();
    }

    public static BigDecimal mortgageBalanceAsOf(PropertyEntity property, LocalDate asOf) {
        if (property.hasLoanDetails()) {
            return remainingBalance(property, asOf);
        }
        return property.getMortgageBalance();
    }

    /**
     * The interest/principal split of the next twelve scheduled mortgage payments after
     * {@code asOf}, or fewer when the loan pays off sooner: interest follows the amortization
     * schedule payment by payment, and no payment past payoff is charged. Empty when the
     * property has no loan details or the loan is already paid off.
     */
    public static Optional<DebtService> annualDebtService(PropertyEntity property, LocalDate asOf) {
        if (!property.hasLoanDetails()) {
            return Optional.empty();
        }
        int termMonths = property.getLoanTermMonths();
        long monthsElapsed = ChronoUnit.MONTHS.between(property.getLoanStartDate(), asOf);
        int paymentsMade = Math.clamp(monthsElapsed, 0, Math.max(termMonths, 0));
        var debtService = AmortizationCalculator.debtServiceForPayments(
                property.getLoanAmount(), property.getAnnualInterestRate(), termMonths,
                paymentsMade + 1, paymentsMade + MONTHS_PER_YEAR);
        return debtService.total().signum() > 0 ? Optional.of(debtService) : Optional.empty();
    }

    /**
     * The mortgage payments due over the next twelve months for a property with loan details
     * (monthly payment &times; 12, or fewer payments once the loan pays off), or
     * {@link BigDecimal#ZERO} when there are none or the loan is paid off.
     */
    public static BigDecimal annualMortgagePayment(PropertyEntity property) {
        return annualDebtService(property, LocalDate.now())
                .map(DebtService::total)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * The nominal interest and principal paid in each calendar year from {@code fromYear} through
     * the payoff year, per the amortization schedule (partial start and payoff years count only
     * their own payments). Empty when the property has no loan details or the loan is paid off
     * before {@code fromYear}.
     */
    public static Map<Integer, DebtService> debtServiceByYear(PropertyEntity property, int fromYear) {
        if (!property.hasLoanDetails()) {
            return Map.of();
        }
        var startDate = property.getLoanStartDate();
        int payoffYear = startDate.plusMonths(Math.max(property.getLoanTermMonths(), 0)).getYear();
        var schedule = new TreeMap<Integer, DebtService>();
        for (int year = fromYear; year <= payoffYear; year++) {
            var debtService = AmortizationCalculator.debtServiceForYear(
                    property.getLoanAmount(), property.getAnnualInterestRate(),
                    property.getLoanTermMonths(), startDate, year);
            if (debtService.total().signum() > 0) {
                schedule.put(year, debtService);
            }
        }
        return Collections.unmodifiableMap(schedule);
    }

    /**
     * The sum of a property's annual property tax, insurance, and maintenance costs
     * (each treated as zero when unset). Consolidates the tax/insurance/maintenance
     * triple that recurred across the ROI, analytics, and scenario services.
     */
    public static BigDecimal annualOperatingExpenses(PropertyEntity property) {
        return Money.sum(
                property.getAnnualPropertyTax(),
                property.getAnnualInsuranceCost(),
                property.getAnnualMaintenanceCost());
    }

    private static BigDecimal remainingBalance(PropertyEntity property, LocalDate asOf) {
        return AmortizationCalculator.remainingBalance(
                property.getLoanAmount(),
                property.getAnnualInterestRate(),
                property.getLoanTermMonths(),
                property.getLoanStartDate(),
                asOf);
    }
}
