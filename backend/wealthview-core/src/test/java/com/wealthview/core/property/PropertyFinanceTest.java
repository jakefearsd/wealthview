package com.wealthview.core.property;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.wealthview.core.common.Money;
import com.wealthview.persistence.entity.PropertyEntity;

import static org.assertj.core.api.Assertions.assertThat;

class PropertyFinanceTest {

    private static final BigDecimal LOAN_AMOUNT = new BigDecimal("400000");
    private static final BigDecimal RATE = new BigDecimal("0.05");
    private static final int TERM_MONTHS = 360;
    private static final LocalDate LOAN_START = LocalDate.of(2020, 6, 1);

    private PropertyEntity propertyWithLoan() {
        var property = propertyWithoutLoan();
        property.setLoanAmount(LOAN_AMOUNT);
        property.setAnnualInterestRate(RATE);
        property.setLoanTermMonths(TERM_MONTHS);
        property.setLoanStartDate(LOAN_START);
        return property;
    }

    private PropertyEntity propertyWithoutLoan() {
        return new PropertyEntity(null, "123 Main St", new BigDecimal("500000"),
                LocalDate.of(2020, 6, 1), new BigDecimal("650000"), new BigDecimal("123456.0000"));
    }

    // ── effectiveCurrentMortgageBalance ────────────────────────────────────

    @Test
    void effectiveCurrentMortgageBalance_computedFlagWithLoanDetails_amortizesToToday() {
        var property = propertyWithLoan();
        property.setUseComputedBalance(true);

        var balance = PropertyFinance.effectiveCurrentMortgageBalance(property);

        var expected = AmortizationCalculator.remainingBalance(
                LOAN_AMOUNT, RATE, TERM_MONTHS, LOAN_START, LocalDate.now());
        assertThat(balance).isEqualByComparingTo(expected);
    }

    @Test
    void effectiveCurrentMortgageBalance_computedFlagOff_returnsManualBalance() {
        var property = propertyWithLoan();
        property.setUseComputedBalance(false);

        var balance = PropertyFinance.effectiveCurrentMortgageBalance(property);

        assertThat(balance).isEqualByComparingTo("123456.0000");
    }

    @Test
    void effectiveCurrentMortgageBalance_computedFlagButNoLoanDetails_returnsManualBalance() {
        var property = propertyWithoutLoan();
        property.setUseComputedBalance(true);

        var balance = PropertyFinance.effectiveCurrentMortgageBalance(property);

        assertThat(balance).isEqualByComparingTo("123456.0000");
    }

    // ── mortgageBalanceAsOf ────────────────────────────────────────────────

    @Test
    void mortgageBalanceAsOf_withLoanDetails_amortizesToDateIgnoringComputedFlag() {
        var property = propertyWithLoan();
        property.setUseComputedBalance(false);
        var asOf = LocalDate.of(2024, 6, 1);

        var balance = PropertyFinance.mortgageBalanceAsOf(property, asOf);

        var expected = AmortizationCalculator.remainingBalance(
                LOAN_AMOUNT, RATE, TERM_MONTHS, LOAN_START, asOf);
        assertThat(balance).isEqualByComparingTo(expected);
    }

    @Test
    void mortgageBalanceAsOf_withoutLoanDetails_returnsManualBalance() {
        var property = propertyWithoutLoan();

        var balance = PropertyFinance.mortgageBalanceAsOf(property, LocalDate.of(2024, 6, 1));

        assertThat(balance).isEqualByComparingTo("123456.0000");
    }

    // ── annualDebtService ──────────────────────────────────────────────────

    @Test
    void annualDebtService_withActiveLoan_splitsInterestAndPrincipal() {
        var property = propertyWithLoan();
        var asOf = LocalDate.of(2024, 6, 1);

        var debtService = PropertyFinance.annualDebtService(property, asOf);

        assertThat(debtService).isPresent();
        var remaining = AmortizationCalculator.remainingBalance(
                LOAN_AMOUNT, RATE, TERM_MONTHS, LOAN_START, asOf);
        var expectedInterest = Money.scale(remaining.multiply(RATE));
        var expectedPayment = AmortizationCalculator.monthlyPayment(LOAN_AMOUNT, RATE, TERM_MONTHS)
                .multiply(new BigDecimal("12"));
        var expectedPrincipal = expectedPayment.subtract(expectedInterest).max(BigDecimal.ZERO);
        assertThat(debtService.get().interest()).isEqualByComparingTo(expectedInterest);
        assertThat(debtService.get().principal()).isEqualByComparingTo(expectedPrincipal);
        assertThat(debtService.get().total())
                .isEqualByComparingTo(expectedInterest.add(expectedPrincipal));
    }

    @Test
    void annualDebtService_withoutLoanDetails_isEmpty() {
        var property = propertyWithoutLoan();

        var debtService = PropertyFinance.annualDebtService(property, LocalDate.now());

        assertThat(debtService).isEmpty();
    }

    @Test
    void annualDebtService_loanFullyPaidOff_isEmpty() {
        var property = propertyWithLoan();
        var afterTerm = LOAN_START.plusMonths(TERM_MONTHS + 1);

        var debtService = PropertyFinance.annualDebtService(property, afterTerm);

        assertThat(debtService).isEmpty();
    }
}
