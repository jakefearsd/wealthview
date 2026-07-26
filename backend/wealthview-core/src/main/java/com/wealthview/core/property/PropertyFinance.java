package com.wealthview.core.property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

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

    private static final BigDecimal MONTHS_PER_YEAR = new BigDecimal("12");

    private PropertyFinance() {
    }

    /** One year of mortgage payments split into its interest and principal parts. */
    public record AnnualDebtService(BigDecimal interest, BigDecimal principal) {
        public BigDecimal total() {
            return interest.add(principal);
        }
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
     * The interest/principal split of one year of mortgage payments starting at
     * {@code asOf}. Empty when the property has no loan details or the loan is
     * already paid off.
     */
    public static Optional<AnnualDebtService> annualDebtService(PropertyEntity property, LocalDate asOf) {
        if (!property.hasLoanDetails()) {
            return Optional.empty();
        }
        var remaining = remainingBalance(property, asOf);
        if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        var interest = Money.scale(remaining.multiply(property.getAnnualInterestRate()));
        var annualPayment = AmortizationCalculator.monthlyPayment(
                property.getLoanAmount(), property.getAnnualInterestRate(), property.getLoanTermMonths())
                .multiply(MONTHS_PER_YEAR);
        var principal = annualPayment.subtract(interest).max(BigDecimal.ZERO);
        return Optional.of(new AnnualDebtService(interest, principal));
    }

    /**
     * The annualized mortgage payment (monthly payment &times; 12) for a property with
     * loan details, or {@link BigDecimal#ZERO} when there are none. Consolidates the
     * {@code monthlyPayment(...).multiply(12)} clump that recurred across the ROI and
     * analytics services.
     */
    public static BigDecimal annualMortgagePayment(PropertyEntity property) {
        if (!property.hasLoanDetails()) {
            return BigDecimal.ZERO;
        }
        return AmortizationCalculator.monthlyPayment(
                property.getLoanAmount(), property.getAnnualInterestRate(), property.getLoanTermMonths())
                .multiply(MONTHS_PER_YEAR);
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
