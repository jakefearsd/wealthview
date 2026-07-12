package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.incomeSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.oneTimeIncomeSource;
import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProjectionEngineIncomeSourceTest extends DeterministicProjectionEngineTestSupport {

    @Test
    void run_withSpendingProfile_incomeSourceReducesNeed() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(bd("25000"));
    }

    @Test
    void run_withSpendingProfile_incomeSourceStartsLater() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "24000", 67, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        // age 67 = startAge → income halved
        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("12000"));
    }

    @Test
    void run_withSpendingProfile_incomeSourceEndsAtEndAge() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Part-time", "30000", 66, 68, "0")));

        var result = engine.run(input);

        // age 66 = startAge → halved
        assertThat(result.yearlyData().getFirst().incomeStreamsTotal())
                .isEqualByComparingTo(bd("15000"));
        // age 67 = mid-range → full
        assertThat(result.yearlyData().get(1).incomeStreamsTotal())
                .isEqualByComparingTo(bd("30000"));
        // age 68 = endAge → halved
        assertThat(result.yearlyData().get(2).incomeStreamsTotal())
                .isEqualByComparingTo(bd("15000"));
        // age 69 = after endAge → zero
        assertThat(result.yearlyData().get(3).incomeStreamsTotal())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // === Income inflation tests ===

    @Test
    void run_withSpendingProfile_incomeInflation_adjustsIncome() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0.02")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("20400.0000"));
    }

    @Test
    void run_withSpendingProfile_zeroIncomeInflation_nominalIncome() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var result = engine.run(input);

        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));
    }

    @Test
    void run_withSpendingProfile_perSourceDifferentRates_inflatesIndependently() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("10000"), null),
                List.of(
                        incomeSource("Social Security", "20000", 60, null, "0.02"),
                        incomeSource("Rental Income", "10000", 60, null, "0.03")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("30000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("30700.0000"));
    }

    // === Income sources affect portfolio withdrawals (Step 2) ===

    @Test
    void run_withIncomeSource_reducesPortfolioWithdrawal() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Spending-needs-driven: need = 30k+15k = 45k, income = 20k, portfolio withdrawal = 25k
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("25000.0000"));
    }

    @Test
    void run_withIncomeSourceCoveringAllSpending_portfolioWithdrawalIsZero() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("10000"), bd("5000"), null),
                List.of(incomeSource("Social Security", "40000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.endBalance()).isGreaterThan(year1.startBalance());
    }

    @Test
    void run_withIncomeSource_endBalanceHigherThanWithout() {
        String params = """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66);

        var inputWithout = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO, params,
                List.of(acct("1000000.0000", "0", "0.0500")));

        var inputWith = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO, params,
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var resultWithout = engine.run(inputWithout);
        var resultWith = engine.run(inputWith);

        assertThat(resultWith.finalBalance()).isGreaterThan(resultWithout.finalBalance());
    }

    @Test
    void run_withoutSpendingProfile_withdrawalUnchanged() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));
    }

    @Test
    void run_withIncomeSourceStartingLater_reducedOnlyAfterStartAge() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "24000", 67, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Spending-needs-driven: need=45k, no income at age 66, withdrawal=45k
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("45000.0000"));

        var year2 = result.yearlyData().get(1);
        // age 67 = startAge: income halved to 12k, need=45k, withdrawal=33k
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("33000.0000"));
    }

    @Test
    void run_withIncomeSourceEnding_withdrawalIncreasesAfterEnd() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Part-time", "20000", 66, 68, "0")));

        var result = engine.run(input);

        // age 66 = startAge → income halved to 10k, need=45k, withdrawal=35k
        assertThat(result.yearlyData().get(0).withdrawals()).isEqualByComparingTo(bd("35000.0000"));
        // age 67 = mid-range → income=20k, withdrawal=25k
        assertThat(result.yearlyData().get(1).withdrawals()).isLessThan(bd("45000"));

        var year3 = result.yearlyData().get(2);
        assertThat(year3.withdrawals()).isGreaterThan(result.yearlyData().get(1).withdrawals());
    }

    @Test
    void run_withIncomeSource_previousWithdrawalTracksSpending() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Spending-needs-driven: need=45k, income=20k, withdrawal=25k
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("25000.0000"));

        var year2 = result.yearlyData().get(1);
        // Real terms: need=45k constant real; fixed-nominal income 20k deflated by 1.03 -> 19417.4757;
        // withdrawal = 45000 - 19417.4757 = 25582.5243
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("25582.5243"));
    }

    @Test
    void run_pools_withIncomeSource_reducesPortfolioWithdrawal() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("500000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var resultWith = engineTax.run(input);

        var inputWithout = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("500000.0000", "0", "0.0500", "roth")));

        var resultWithout = engineTax.run(inputWithout);

        assertThat(resultWith.yearlyData().getFirst().withdrawals())
                .isLessThan(resultWithout.yearlyData().getFirst().withdrawals());
    }

    @Test
    void run_pools_withIncomeSource_endBalanceHigherThanWithout() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        String params = """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66);

        var inputWithout = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO, params,
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("500000.0000", "0", "0.0500", "roth")));

        var inputWith = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO, params,
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("500000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var resultWithout = engineTax.run(inputWithout);
        var resultWith = engineTax.run(inputWith);

        assertThat(resultWith.finalBalance()).isGreaterThan(resultWithout.finalBalance());
    }

    @Test
    void run_pools_withIncomeSource_taxIncludesActiveIncome() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var inputWith = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("500000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var resultWith = engineTax.run(inputWith);

        var year1With = resultWith.yearlyData().getFirst();
        assertThat(year1With.taxLiability()).isNotNull();
        assertThat(year1With.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    // --- Income Source Integration Tests ---

    @Test
    void run_withSocialSecurityIncomeSource_reducesWithdrawals() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 67; // already retired at 67

        var spending = new SpendingProfileInput(bd("40000"), bd("20000"), "[]");

        var ssSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Social Security", IncomeSourceType.SOCIAL_SECURITY,
                bd("30000"), 60, null, bd("0.02"), false,
                "partially_taxable",
                null, null, null, null, null, null);

        var input = createInput(
                LocalDate.of(currentYear - 1, 1, 1), 80, bd("0.03"),
                """
                {"birth_year": %d}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.05")),
                spending, currentYear, List.of(ssSource));

        var result = engine.run(input);
        assertThat(result.yearlyData()).isNotEmpty();

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        // SS provides $30k+ cash (mid-range, not boundary), spending is $60k, so ~$30k from portfolio
        assertThat(year1.withdrawals()).isLessThan(bd("35000"));
        // Income streams total should include SS cash inflow
        assertThat(year1.incomeStreamsTotal()).isNotNull();
        assertThat(year1.incomeStreamsTotal()).isGreaterThanOrEqualTo(bd("30000"));
    }

    @Test
    void run_withRentalIncomeAndDepreciation_showsDepreciationShield() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 65;

        var spending = new SpendingProfileInput(bd("50000"), bd("10000"), "[]");

        var depreciationSchedule = Map.of(
                currentYear, bd("10000"),
                currentYear + 1, bd("10000"),
                currentYear + 2, bd("10000"));

        var rentalSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental Property", IncomeSourceType.RENTAL_PROPERTY,
                bd("24000"), 60, null, BigDecimal.ZERO, false,
                "rental_passive",
                bd("6000"), bd("4000"), null, bd("3000"),
                "straight_line", depreciationSchedule);

        var input = createInput(
                LocalDate.of(currentYear - 1, 1, 1), 80, bd("0.03"),
                """
                {"birth_year": %d}
                """.formatted(birthYear),
                List.of(acct("500000", "0", "0.05")),
                spending, currentYear, List.of(rentalSource));

        var result = engine.run(input);
        var year1 = result.yearlyData().getFirst();

        // Rental cash flow = 24000 - 6000 - 4000 - 3000 = 11000
        assertThat(year1.rentalIncomeGross()).isEqualByComparingTo("24000");
        assertThat(year1.rentalExpensesTotal()).isEqualByComparingTo("13000");
        assertThat(year1.depreciationTotal()).isEqualByComparingTo("10000");
    }

    @Test
    void run_withMultipleIncomeSources_combinesCorrectly() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 68;

        var spending = new SpendingProfileInput(bd("50000"), bd("10000"), "[]");

        var ssSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "SS", IncomeSourceType.SOCIAL_SECURITY,
                bd("24000"), 67, null, bd("0.02"), false,
                "partially_taxable",
                null, null, null, null, null, null);

        var pensionSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Pension", IncomeSourceType.PENSION,
                bd("20000"), 65, null, BigDecimal.ZERO, false,
                "taxable",
                null, null, null, null, null, null);

        var input = createInput(
                LocalDate.of(currentYear - 2, 1, 1), 85, bd("0.03"),
                """
                {"birth_year": %d}
                """.formatted(birthYear),
                List.of(acct("400000", "0", "0.05")),
                spending, currentYear, List.of(ssSource, pensionSource));

        var result = engine.run(input);
        var year1 = result.yearlyData().getFirst();

        // Total income should include both SS and pension cash flows
        assertThat(year1.incomeStreamsTotal()).isNotNull();
        assertThat(year1.incomeStreamsTotal()).isGreaterThanOrEqualTo(bd("44000"));
        // With $44k+ income, withdrawals should be minimal for $60k spending
        assertThat(year1.withdrawals()).isLessThan(bd("20000"));
    }

    @Test
    void run_withOneTimeIncomeSource_firesOnlyAtStartAge() {
        // Person is already retired at age 66, one-time income at age 67
        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(oneTimeIncomeSource("Inheritance", "50000", 67)));

        var result = engine.run(input);

        // Year 1: age 66 — one-time source not yet active
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        // Year 2: age 67 — one-time source fires (full amount, not halved)
        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("50000"));

        // Year 3: age 68 — one-time source must NOT fire again
        var year3 = result.yearlyData().get(2);
        assertThat(year3.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        // Verify exactly one year has the income across all years
        long yearsWithIncome = result.yearlyData().stream()
                .filter(y -> y.incomeStreamsTotal() != null
                        && y.incomeStreamsTotal().compareTo(BigDecimal.ZERO) > 0)
                .count();
        assertThat(yearsWithIncome).isEqualTo(1);
    }

    // === Audit C7: base-year-anchored deflation clock ===

    @Test
    void run_zeroColaPension_retirement15YearsOut_deflatesFromBaseYear() {
        // A 0%-COLA pension active since the base year (age 50 == startAge, so no boundary
        // halving by the time retirement starts). Retirement is 15 CALENDAR years after the
        // projection's base year (2026 -> 2041). Pre-C7, the accumulation-phase clock
        // (yearsInRetirement) stayed pinned at 0 the entire time, so the first retirement year
        // (yearsInRetirement=1 -> steps=0) still paid the full $10,000 face value -- zero
        // deflation despite 15 real calendar years having passed. Fixed: the clock is now
        // (taxYear - baseYear), so year 1 of retirement (2041) deflates by 15 years of 3%
        // scenario inflation: 10000 / 1.03^15 = 6418.6195.
        var input = createInput(
                LocalDate.of(2041, 1, 1), 90, bd("0.03"),
                "{\"birth_year\": 1976}",
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("0"), bd("0"), null),
                2026,
                List.of(incomeSource("Pension", "10000", 50, null, "0")));

        var result = engine.run(input);

        var firstRetirementYear = result.yearlyData().get(15);
        assertThat(firstRetirementYear.age()).isEqualTo(65);
        assertThat(firstRetirementYear.retired()).isTrue();
        assertThat(firstRetirementYear.incomeStreamsTotal()).isEqualByComparingTo(bd("6418.6195"));
    }

    @Test
    void run_cpiMatchedPension_retirement15YearsOut_staysConstantRealAcrossBoundary() {
        // Invariance pin (audit C7): a source whose OWN inflation rate exactly matches scenario
        // inflation must stay at EXACTLY its face value forever -- including across the
        // accumulation/retirement boundary -- because growth and deflation now share one
        // calendar-anchored clock. (Pre-C7 this happened to hold too, but only because the buggy
        // clock stayed at 0 the whole time for BOTH growth and deflation during accumulation; the
        // zero-COLA test above proves this run is exercising the real fix, not a no-op.)
        var input = createInput(
                LocalDate.of(2041, 1, 1), 90, bd("0.03"),
                "{\"birth_year\": 1976}",
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("0"), bd("0"), null),
                2026,
                List.of(incomeSource("Pension", "10000", 50, null, "0.03")));

        var result = engine.run(input);

        var firstRetirementYear = result.yearlyData().get(15);
        assertThat(firstRetirementYear.age()).isEqualTo(65);
        assertThat(firstRetirementYear.incomeStreamsTotal()).isEqualByComparingTo(bd("10000"));

        var laterRetirementYear = result.yearlyData().get(20);
        assertThat(laterRetirementYear.incomeStreamsTotal()).isEqualByComparingTo(bd("10000"));
    }

    // === Surplus Income Reinvestment Tests ===

    @Test
    void run_withSpendingProfile_incomeExceedsSpending_depositsGrossSurplus() {
        // No tax calculator: gross surplus is deposited in full.
        // Spending = $30k, Income = $50k (mid-range age, full amount) → surplus = $20k
        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Pension", "50000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("20000"));
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withSpendingProfile_incomeUnderSpending_surplusReinvestedIsNull() {
        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), null),
                List.of(incomeSource("Pension", "20000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.surplusReinvested()).isNull();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("25000.0000"));
    }

    @Test
    void run_withSpendingProfile_incomeEqualsSpending_surplusReinvestedIsNull() {
        // Income exactly covers spending: no surplus deposited, no withdrawal
        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Pension", "30000", 60, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.surplusReinvested()).isNull();
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withSpendingProfile_surplusWithTaxCalc_depositsAfterTaxAmount() {
        // MultiPool with tax calculator: tax is deducted before depositing surplus.
        // Spending = $30k, Income = $50k → gross surplus = $20k
        // Tax on $50k SINGLE 2025 (standard ded $15k):
        //   taxable = $50k - $15k = $35k
        //   10%: $11,925 × 0.10 = $1,192.50
        //   12%: $23,075 × 0.12 = $2,769.00 → total = $3,961.50
        // After-tax surplus = $20,000 - $3,961.50 = $16,038.50
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
                """.formatted(birthYear),
                List.of(
                        acct("300000.0000", "0", "0.0500", "traditional"),
                        acct("200000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Pension", "50000", 60, null, "0")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.surplusReinvested()).isNotNull();
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("16038.5000"));
        // Taxable account balance should equal the deposited after-tax surplus
        // (starts at 0, grows at 0, then receives deposit)
        assertThat(year1.taxableBalance()).isEqualByComparingTo(bd("16038.5000"));
    }

    @Test
    void run_withSpendingProfile_pensionRefundSpike_surplusReinvestedOnlyInSpikeYear() {
        // One-time pension refund at age 67: $100k income vs $30k spending → $70k surplus (year 2).
        // All other years: no surplus (income = 0 < spending).
        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(oneTimeIncomeSource("Pension Refund", "100000", 67)));

        var result = engine.run(input);

        // Year 1 (age 66): no one-time income → no surplus
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.surplusReinvested()).isNull();

        // Year 2 (age 67): one-time $100k > $30k spending → surplus = $70k
        var year2 = result.yearlyData().get(1);
        assertThat(year2.surplusReinvested()).isEqualByComparingTo(bd("70000"));

        // Year 3 (age 68): one-time source expired → no surplus
        var year3 = result.yearlyData().get(2);
        assertThat(year3.surplusReinvested()).isNull();
    }

    @Test
    void run_withoutSpendingProfile_incomeSource_noSurplusReinvested() {
        // On the withdrawal-strategy path (no spending profile), surplus is never deposited.
        var birthYear = LocalDate.now().getYear() - 66;
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("500000.0000", "0", "0.0500")));

        var result = engine.run(input);

        result.yearlyData().forEach(year ->
                assertThat(year.surplusReinvested()).isNull());
    }

    @Test
    void run_withSpendingProfile_surplusIncreasesSubsequentYearBalances() {
        // Surplus deposited in year 2 compounds into subsequent years.
        // Without surplus: balance grows from portfolio alone.
        // With surplus: balance in year 3+ is higher by at least the deposited amount.
        var birthYear = LocalDate.now().getYear() - 66;
        String params = """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear);

        var inputWithout = createInput(
                LocalDate.now().minusYears(1), 70, BigDecimal.ZERO, params,
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null));

        var inputWith = createInput(
                LocalDate.now().minusYears(1), 70, BigDecimal.ZERO, params,
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(oneTimeIncomeSource("Pension Refund", "100000", 67)));

        var resultWithout = engine.run(inputWithout);
        var resultWith = engine.run(inputWith);

        // Year 2 (spike year): balance with surplus > without
        assertThat(resultWith.yearlyData().get(1).endBalance())
                .isGreaterThan(resultWithout.yearlyData().get(1).endBalance());

        // Year 3: balance with surplus is still higher (surplus compounded)
        assertThat(resultWith.yearlyData().get(2).endBalance())
                .isGreaterThan(resultWithout.yearlyData().get(2).endBalance());
    }
}
