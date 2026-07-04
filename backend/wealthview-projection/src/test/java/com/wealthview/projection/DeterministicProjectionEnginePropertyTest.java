package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInputWithProperties;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.property;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.propertyNoLoan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class DeterministicProjectionEnginePropertyTest extends DeterministicProjectionEngineTestSupport {

    // ── Property Equity and Net Worth Tests ──

    @Test
    void run_withPropertyNoLoan_equityEqualsCurrentValueEachYear() {
        // Property worth 300,000 with no mortgage, 0% appreciation → equity stays 300,000
        var prop = propertyNoLoan("300000", "0.00", "0");
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;
        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.05")),
                List.of(prop));

        var result = engine.run(input);

        for (var year : result.yearlyData()) {
            assertThat(year.propertyEquity())
                    .as("year %d property equity", year.year())
                    .isEqualByComparingTo(bd("300000"));
        }
    }

    @Test
    void run_withPropertyAppreciation_equityGrowsEachYear() {
        // Property worth 300,000 with 5% appreciation, no mortgage
        var prop = propertyNoLoan("300000", "0.05", "0");
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;
        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.05")),
                List.of(prop));

        var result = engine.run(input);

        var year1 = result.yearlyData().get(0);
        var year2 = result.yearlyData().get(1);

        // After 1 year of appreciation, equity should be higher
        assertThat(year2.propertyEquity()).isGreaterThan(year1.propertyEquity());
        // Year 2 equity ≈ 300,000 * 1.05 = 315,000
        assertThat(year2.propertyEquity())
                .isEqualByComparingTo(bd("315000"));
    }

    @Test
    void run_withProperty_totalNetWorthIncludesPropertyEquity() {
        // Portfolio: 500,000; property: 300,000 no mortgage — net worth = 800,000 in year 1
        var prop = propertyNoLoan("300000", "0.00", "0");
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;
        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.00")),
                List.of(prop));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.totalNetWorth())
                .isEqualByComparingTo(year1.endBalance().add(year1.propertyEquity()));
    }

    @Test
    void run_withMultipleProperties_sumsBothEquities() {
        // Two properties, 200,000 each with no mortgage, 0% appreciation
        var prop1 = propertyNoLoan("200000", "0.00", "0");
        var prop2 = propertyNoLoan("150000", "0.00", "0");
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;
        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.00")),
                List.of(prop1, prop2));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.propertyEquity()).isEqualByComparingTo(bd("350000"));
    }

    @Test
    void run_withNoProperties_propertyEquityIsZeroOrNull() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;
        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.00")),
                List.of());

        var result = engine.run(input);

        for (var year : result.yearlyData()) {
            assertThat(year.propertyEquity() == null
                    || year.propertyEquity().compareTo(BigDecimal.ZERO) == 0).isTrue();
        }
    }

    @Test
    void run_withProperty_finalNetWorthSetOnResult() {
        var prop = propertyNoLoan("300000", "0.00", "0");
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;
        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.00")),
                List.of(prop));

        var result = engine.run(input);

        assertThat(result.finalNetWorth()).isNotNull();
        var lastYear = result.yearlyData().getLast();
        assertThat(result.finalNetWorth()).isEqualByComparingTo(lastYear.totalNetWorth());
    }

    // === Coverage gap: Property equity computation ===

    @Test
    void run_singlePropertyWithAppreciationOver10Years_equityEqualsAppreciatedValueMinusMortgage() {
        // Property worth $400K, 3% annual appreciation, loan $300K at 5% over 30 years
        // After 10 years: appreciated value = $400K * 1.03^10 ≈ $537,566.55
        // Remaining mortgage computed by AmortizationCalculator
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 55;
        var loanStart = LocalDate.of(currentYear, 1, 1);
        var prop = property("400000", "0.03", "300000", "0.05", 360, loanStart);

        var input = createInputWithProperties(
                LocalDate.of(currentYear + 5, 1, 1), 80, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.00")),
                List.of(prop));

        var result = engine.run(input);

        // Check year at index 10 (yearsElapsed = 10)
        assertThat(result.yearlyData().size()).isGreaterThan(10);
        var year10 = result.yearlyData().get(10);

        // Appreciated value after 10 years: 400000 * 1.03^10
        BigDecimal appreciatedValue = bd("400000").multiply(
                BigDecimal.ONE.add(bd("0.03")).pow(10));

        assertThat(year10.propertyEquity()).isNotNull();
        // Equity = appreciated value - remaining mortgage; mortgage has been partly paid
        // So equity should be less than appreciated value but positive
        assertThat(year10.propertyEquity()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year10.propertyEquity()).isLessThan(appreciatedValue);
        // Equity > initial equity (400K - 300K = 100K) due to appreciation + principal paydown
        assertThat(year10.propertyEquity()).isGreaterThan(bd("100000"));
    }

    @Test
    void run_propertyWithNoMortgage_equityEqualsAppreciatedValue() {
        // Property worth $500K, 4% annual appreciation, no mortgage (mortgageBalance = 0)
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 60;
        var prop = propertyNoLoan("500000", "0.04", "0");

        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 70, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("300000", "0", "0.00")),
                List.of(prop));

        var result = engine.run(input);

        // Year 0 (yearsElapsed = 0): equity = 500000 * 1.04^0 = 500000
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.propertyEquity()).isEqualByComparingTo(bd("500000"));

        // Year 5 (yearsElapsed = 5): equity = 500000 * 1.04^5
        var year5 = result.yearlyData().get(5);
        BigDecimal expected5yr = bd("500000").multiply(BigDecimal.ONE.add(bd("0.04")).pow(5));
        assertThat(year5.propertyEquity()).isEqualByComparingTo(expected5yr.setScale(4, RoundingMode.HALF_UP));
    }

    @Test
    void run_multipleProperties_totalEquitySummed() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;

        // Property A: $200K, 3% appreciation, no mortgage
        var propA = propertyNoLoan("200000", "0.03", "0");
        // Property B: $300K, 5% appreciation, no mortgage
        var propB = propertyNoLoan("300000", "0.05", "0");

        var input = createInputWithProperties(
                LocalDate.of(currentYear - 1, 1, 1), 70, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("400000", "0", "0.00")),
                List.of(propA, propB));

        var result = engine.run(input);

        // Year 0 (yearsElapsed = 0): total = 200000 + 300000 = 500000
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.propertyEquity()).isEqualByComparingTo(bd("500000"));

        // Year 3 (yearsElapsed = 3): total = 200000*1.03^3 + 300000*1.05^3
        var year3 = result.yearlyData().get(3);
        BigDecimal expectedA = bd("200000").multiply(BigDecimal.ONE.add(bd("0.03")).pow(3));
        BigDecimal expectedB = bd("300000").multiply(BigDecimal.ONE.add(bd("0.05")).pow(3));
        BigDecimal expectedTotal = expectedA.add(expectedB).setScale(4, RoundingMode.HALF_UP);
        assertThat(year3.propertyEquity()).isEqualByComparingTo(expectedTotal);
    }

    @Test
    void run_noProperties_propertyEquityAndTotalNetWorthNull() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;

        var input = createInput(
                LocalDate.of(currentYear - 1, 1, 1), 68, bd("0.00"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.05")));

        var result = engine.run(input);

        for (var year : result.yearlyData()) {
            assertThat(year.propertyEquity()).isNull();
            // totalNetWorth should also be null when no properties are present
            assertThat(year.totalNetWorth()).isNull();
        }
    }
}
