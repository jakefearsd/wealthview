package com.wealthview.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProjectionEngineBasicsTest extends DeterministicProjectionEngineTestSupport {

    @Test
    void run_singleAccountPreRetirement_growsWithContributions() {
        var input = createInput(
                LocalDate.now().plusYears(30), 90, bd("0.0300"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(acct("100000.0000", "10000.0000", "0.0700")));

        var result = engine.run(input);

        assertThat(result.scenarioId()).isEqualTo(input.scenarioId());
        assertThat(result.yearlyData()).isNotEmpty();

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(bd("100000"));
        assertThat(year1.contributions()).isEqualByComparingTo(bd("10000"));
        assertThat(year1.retired()).isFalse();
        // Real terms: 0.07 nominal override deflated at 2.5% CMA inflation → 1.07/1.025-1 real.
        // growth = (100000 + 10000) * 0.04390244 = 4829.2684
        assertThat(year1.growth()).isEqualByComparingTo(bd("4829.2684"));
        assertThat(year1.endBalance()).isEqualByComparingTo(bd("114829.2684"));

        assertThat(result.yearlyData()).hasSizeGreaterThan(30);
        assertThat(result.yearsInRetirement()).isGreaterThan(0);
    }

    @Test
    void run_postRetirement_withdrawsConstantRealNoNominalEscalation() {
        var input = createInput(
                LocalDate.now().minusYears(1), 90, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        assertThat(result.yearlyData()).isNotEmpty();

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        assertThat(year1.contributions()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));

        if (result.yearlyData().size() > 1) {
            var year2 = result.yearlyData().get(1);
            // Real terms: the first-year withdrawal is held constant real, no nominal escalation.
            assertThat(year2.withdrawals()).isEqualByComparingTo(bd("40000.0000"));
        }

        assertThat(result.yearsInRetirement()).isGreaterThan(0);
    }

    @Test
    void run_retirementYearBoundary_yearIsRetiredOnRetirementYearNotBefore() {
        // Boundary: retired = year >= retirementYear. The year EQUAL to the
        // retirement year must be retired; the year immediately before must
        // not be. A mutant flipping >= to > would make the retirement year
        // itself still "working".
        int retirementYear = LocalDate.now().getYear() + 5;
        var input = createInput(
                LocalDate.of(retirementYear, 1, 1), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 60),
                List.of(acct("1000000.0000", "10000.0000", "0.0500")));

        var result = engine.run(input);

        var lastWorking = result.yearlyData().stream()
                .filter(y -> y.year() == retirementYear - 1).findFirst().orElseThrow();
        var firstRetired = result.yearlyData().stream()
                .filter(y -> y.year() == retirementYear).findFirst().orElseThrow();

        assertThat(lastWorking.retired()).isFalse();
        assertThat(firstRetired.retired()).isTrue();
    }

    @Test
    void run_preRetirementYear_appliesContributionsButNoWithdrawals() {
        // Pins the !retired contribution branch: a working year must add
        // contributions and take zero withdrawals.
        int retirementYear = LocalDate.now().getYear() + 10;
        var input = createInput(
                LocalDate.of(retirementYear, 1, 1), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 55),
                List.of(acct("500000.0000", "12000.0000", "0.0500")));

        var result = engine.run(input);
        var workingYear = result.yearlyData().getFirst();

        assertThat(workingYear.retired()).isFalse();
        assertThat(workingYear.contributions()).isEqualByComparingTo(bd("12000"));
        assertThat(workingYear.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withMalformedNumericInParamsJson_usesDefaults() {
        var input = createInput(
                LocalDate.now().minusYears(1), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": "abc"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        assertThat(result.yearlyData()).isNotEmpty();
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        // Default withdrawal rate is 0.04, so withdrawal should be 4% of 1,000,000 = 40,000
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));
    }

    @Test
    void run_multipleAccounts_aggregatesCorrectly() {
        var input = createInput(
                LocalDate.now().plusYears(20), 80, bd("0.0200"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 40),
                List.of(
                        acct("200000.0000", "5000.0000", "0.0800"),
                        acct("100000.0000", "3000.0000", "0.0400")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(bd("300000"));
        assertThat(year1.contributions()).isEqualByComparingTo(bd("8000"));

        // Real terms: 0.08 and 0.04 nominal overrides deflated at 2.5% CMA inflation.
        assertThat(year1.growth().setScale(0, RoundingMode.HALF_UP))
                .isEqualByComparingTo(bd("12520"));
    }

    @Test
    void run_balanceReachesZero_stopsAtZero() {
        var input = createInput(
                LocalDate.now().minusYears(1), 95, bd("0.0200"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.20}
                """.formatted(LocalDate.now().getYear() - 70),
                List.of(acct("100000.0000", "0", "0.0300")));

        var result = engine.run(input);

        var lastYear = result.yearlyData().getLast();
        assertThat(lastYear.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        var balanceDeclined = result.yearlyData().stream()
                .anyMatch(y -> y.endBalance().compareTo(y.startBalance()) < 0);
        assertThat(balanceDeclined).isTrue();

        assertThat(result.finalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void run_zeroRealReturn_onlyContributionsAndWithdrawals() {
        // Real terms: a 0% REAL return requires a nominal override equal to CMA inflation (2.5%),
        // since realReturn = (1+nominal)/(1+inflation) - 1. This isolates the no-growth arithmetic.
        var input = createInput(
                LocalDate.now().plusYears(10), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 30),
                List.of(acct("50000.0000", "5000.0000", "0.025")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(bd("50000"));
        assertThat(year1.contributions()).isEqualByComparingTo(bd("5000"));
        assertThat(year1.growth()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.endBalance()).isEqualByComparingTo(bd("55000"));
    }

    @Test
    void run_multipleAccountTypes_tracksPoolsSeparately() {
        var input = createInput(
                LocalDate.now().plusYears(5), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("200000.0000", "10000.0000", "0.0700", "traditional"),
                        acct("100000.0000", "5000.0000", "0.0700", "roth")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.traditionalBalance()).isNotNull();
        assertThat(year1.rothBalance()).isNotNull();
        assertThat(year1.taxableBalance()).isNotNull();
        assertThat(year1.traditionalBalance()).isGreaterThan(year1.rothBalance());
    }

    @Test
    void run_noAccountTypes_backwardsCompatible() {
        var input = createInput(
                LocalDate.now().plusYears(30), 90, bd("0.0300"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(acct("100000.0000", "10000.0000", "0.0700")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.traditionalBalance()).isNull();
        assertThat(year1.rothBalance()).isNull();
        assertThat(year1.taxableBalance()).isNull();
    }

    // ── Per-Pool Transparency Tests ──

    @Test
    void run_multiPool_exposesPerPoolGrowth() {
        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth"),
                        acct("50000.0000", "0", "0.0500", "taxable")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Real terms: 0.05 nominal override deflated at 2.5% CMA inflation → 1.05/1.025-1 = 0.02439024.
        assertThat(year1.traditionalGrowth()).isEqualByComparingTo(bd("4878.0480"));
        assertThat(year1.rothGrowth()).isEqualByComparingTo(bd("2439.0240"));
        assertThat(year1.taxableGrowth()).isEqualByComparingTo(bd("1219.5120"));
        assertThat(year1.growth()).isEqualByComparingTo(
                year1.traditionalGrowth().add(year1.rothGrowth()).add(year1.taxableGrowth()));
    }

    @Test
    void run_singlePool_perPoolFieldsAreNull() {
        var input = createInput(
                LocalDate.now().plusYears(30), 90, bd("0.0300"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(acct("100000.0000", "10000.0000", "0.0700")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxableGrowth()).isNull();
        assertThat(year1.traditionalGrowth()).isNull();
        assertThat(year1.rothGrowth()).isNull();
        assertThat(year1.taxPaidFromTaxable()).isNull();
        assertThat(year1.taxPaidFromTraditional()).isNull();
        assertThat(year1.taxPaidFromRoth()).isNull();
        assertThat(year1.withdrawalFromTaxable()).isNull();
        assertThat(year1.withdrawalFromTraditional()).isNull();
        assertThat(year1.withdrawalFromRoth()).isNull();
    }
}
