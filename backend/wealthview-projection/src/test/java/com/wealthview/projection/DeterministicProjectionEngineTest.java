package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.incomeSource;
import static com.wealthview.projection.testutil.TierJsonBuilder.tiers;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class DeterministicProjectionEngineTest {

    private DeterministicProjectionEngine engine;
    private TaxBracketRepository taxBracketRepository;
    private StandardDeductionRepository standardDeductionRepository;

    @BeforeEach
    void setUp() {
        engine = new DeterministicProjectionEngine(null);
        taxBracketRepository = mock(TaxBracketRepository.class);
        standardDeductionRepository = mock(StandardDeductionRepository.class);
    }

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
        assertThat(year1.growth()).isEqualByComparingTo(bd("7700.0000"));
        assertThat(year1.endBalance()).isEqualByComparingTo(bd("117700.0000"));

        assertThat(result.yearlyData()).hasSizeGreaterThan(30);
        assertThat(result.yearsInRetirement()).isGreaterThan(0);
    }

    @Test
    void run_postRetirement_withdrawsAndAdjustsForInflation() {
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
            assertThat(year2.withdrawals()).isEqualByComparingTo(bd("41200.0000"));
        }

        assertThat(result.yearsInRetirement()).isGreaterThan(0);
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

        assertThat(year1.growth().setScale(0, RoundingMode.HALF_UP))
                .isEqualByComparingTo(bd("20533"));
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
    void run_zeroReturn_onlyContributionsAndWithdrawals() {
        var input = createInput(
                LocalDate.now().plusYears(10), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 30),
                List.of(acct("50000.0000", "5000.0000", "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(bd("50000"));
        assertThat(year1.contributions()).isEqualByComparingTo(bd("5000"));
        assertThat(year1.growth()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.endBalance()).isEqualByComparingTo(bd("55000"));
    }

    @Test
    void run_dynamicPercentageStrategy_withdrawsPercentOfCurrentBalance() {
        var input = createInput(
                LocalDate.now().minusYears(1), 90, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "dynamic_percentage"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("42000.0000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("42336.0000"));
    }

    @Test
    void run_vanguardStrategy_capsIncreasesAndFloorsDecreases() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "vanguard_dynamic_spending", "dynamic_ceiling": 0.05, "dynamic_floor": -0.025}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("42000.0000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("42336.0000"));
    }

    @Test
    void run_noStrategySpecified_defaultsToFixedPercentage() {
        var input = createInput(
                LocalDate.now().minusYears(1), 90, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));

        if (result.yearlyData().size() > 1) {
            var year2 = result.yearlyData().get(1);
            assertThat(year2.withdrawals()).isEqualByComparingTo(bd("41200.0000"));
        }
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
    void run_rothConversion_movesFromTraditionalToRoth() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("50000"));
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_rothConversionExceedsTraditionalBalance_convertsOnlyAvailable() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 500000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("30000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isLessThanOrEqualTo(bd("32100.0001"));
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

    @Test
    void run_allRothPortfolio_noTaxOnWithdrawals() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("500000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        for (var yearData : result.yearlyData()) {
            if (yearData.taxLiability() != null) {
                assertThat(yearData.taxLiability()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    @Test
    void run_withSpendingProfile_computesViabilityFields() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), "[]"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isNotNull();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("30000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("15000"));
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(bd("45000"));
        // Spending-needs-driven: withdrawals = spending need = 45000, surplus = 0
        assertThat(year1.spendingSurplus()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withSpendingProfile_shortfallCutsDiscretionary() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"), "[]"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Spending-needs-driven: withdrawals = spending need, so no cuts needed
        assertThat(year1.discretionaryAfterCuts()).isEqualByComparingTo(bd("15000.0000"));
    }

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

        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("24000"));
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

        assertThat(result.yearlyData().getFirst().incomeStreamsTotal())
                .isEqualByComparingTo(bd("30000"));
        assertThat(result.yearlyData().get(1).incomeStreamsTotal())
                .isEqualByComparingTo(bd("30000"));
        assertThat(result.yearlyData().get(2).incomeStreamsTotal())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withoutSpendingProfile_viabilityFieldsNull() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        for (var yearData : result.yearlyData()) {
            assertThat(yearData.essentialExpenses()).isNull();
            assertThat(yearData.discretionaryExpenses()).isNull();
            assertThat(yearData.incomeStreamsTotal()).isNull();
            assertThat(yearData.netSpendingNeed()).isNull();
            assertThat(yearData.spendingSurplus()).isNull();
            assertThat(yearData.discretionaryAfterCuts()).isNull();
        }
    }

    @Test
    void run_withdrawalOrderTaxableFirst_drawsTaxableBeforeTraditional() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "taxable_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
    }

    @Test
    void run_withdrawalOrderTraditionalFirst_drawsTraditionalFirst() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.traditionalBalance()).isLessThan(bd("210000"));
        BigDecimal tradReduction = bd("210000").subtract(year1.traditionalBalance());
        BigDecimal taxableReduction = bd("315000").subtract(year1.taxableBalance());
        assertThat(tradReduction).isGreaterThan(taxableReduction);
    }

    @Test
    void run_withdrawalOrderRothFirst_drawsRothFirst() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "roth_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxableBalance()).isEqualByComparingTo(bd("315000.0000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
        assertThat(year1.rothBalance()).isLessThan(bd("105000"));
    }

    @Test
    void run_withdrawalOrderProRata_withdrawsProportionally() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        assertThat(year1.traditionalBalance()).isLessThan(bd("210000"));
        assertThat(year1.rothBalance()).isLessThan(bd("105000"));
    }

    @Test
    void run_withdrawalOrderProRata_taxOnTraditionalPortion() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Use larger balances so traditional portion exceeds standard deduction
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10, "filing_status": "single", "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_fillBracketStrategy_convertsToFillBracket() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        // 12% bracket ceiling $48,475 + standard deduction $15,000 = $63,475
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("63475"));
    }

    @Test
    void run_fillBracketStrategy_withOtherIncome_reducesConversion() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 30000, "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        // ceiling=$63,475, other_income=$30K, conversion=$33,475
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("33475"));
    }

    @Test
    void run_taxableFirstWithdrawal_noTaxOnTaxableWithdrawals() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "taxable_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
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

    // === Spending feasibility tests ===

    @Test
    void run_withSpendingProfile_feasible_returnsSummary() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("15000"), "[]"));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
        assertThat(result.spendingFeasibility().firstShortfallYear()).isNull();
        assertThat(result.spendingFeasibility().firstShortfallAge()).isNull();
        assertThat(result.spendingFeasibility().sustainableAnnualSpending())
                .isGreaterThanOrEqualTo(bd("35000"));
        assertThat(result.spendingFeasibility().requiredAnnualSpending())
                .isEqualByComparingTo(bd("35000"));
    }

    @Test
    void run_withSpendingProfile_infeasible_reportsShortfall() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("100000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("50000"), bd("30000"), "[]"));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isFalse();
        assertThat(result.spendingFeasibility().firstShortfallYear()).isNotNull();
        assertThat(result.spendingFeasibility().firstShortfallAge()).isNotNull();
        assertThat(result.spendingFeasibility().requiredAnnualSpending())
                .isEqualByComparingTo(bd("80000"));
    }

    @Test
    void run_withoutSpendingProfile_feasibilityNull() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNull();
    }

    @Test
    void run_withSpendingProfile_zeroSpending_feasible() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("100000.0000", "0", "0.0500")),
                new SpendingProfileInput(BigDecimal.ZERO, BigDecimal.ZERO, "[]"));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
        assertThat(result.spendingFeasibility().requiredAnnualSpending())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withSpendingProfile_incomeCoversAll_feasible() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "40000", 60, null, "0")));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
    }

    @Test
    void run_withSpendingProfile_delayedIncome_shortfallWhenBalanceDepleted() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 65),
                List.of(acct("100000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("25000"), bd("15000"), null),
                List.of(incomeSource("Social Security", "30000", 67, null, "0")));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isFalse();
        assertThat(result.spendingFeasibility().firstShortfallAge()).isNotNull();
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
        // age 67: income=24k starts, need=45k, withdrawal=21k
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("21000.0000"));
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

        // Spending-needs-driven: need=45k, income=20k, withdrawal=25k
        assertThat(result.yearlyData().get(0).withdrawals()).isEqualByComparingTo(bd("25000.0000"));
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
        // Year 2: need=45k*1.03=46350, income=20k (0 inflation), withdrawal=26350
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("26350.0000"));
    }

    // === Income sources affect pools/Roth/tax (Step 3) ===

    @Test
    void run_fillBracket_incomeSourceReducesBracketSpace() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "30000", 60, null, "0")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("33475"));
    }

    @Test
    void run_fillBracket_incomeSourceAndOtherIncome_bothReduceSpace() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 10000, "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("20000"), bd("10000"), null),
                List.of(incomeSource("Social Security", "20000", 60, null, "0")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("33475"));
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

    @Test
    void run_withSpendingProfile_withInflation_sustainableDeflated() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("20000"), bd("15000"), "[]"));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().sustainableAnnualSpending()).isPositive();
    }

    // === Spending Tiers Tests ===

    @Test
    void run_withSpendingTiers_usesCorrectTierForAge() {
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 62, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 55),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("96000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("0.0000"));
    }

    @Test
    void run_withSpendingTiers_transitionBetweenTiers() {
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 62, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 61),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("96000.0000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.essentialExpenses()).isEqualByComparingTo(bd("156000.0000"));
        assertThat(year2.discretionaryExpenses()).isEqualByComparingTo(bd("60000.0000"));
    }

    @Test
    void run_withSpendingTiers_fallsBackToFlatWhenNoTierMatches() {
        var tierJson = tiers()
                .tier("Go-Go", 62, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 55),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("20000.0000"));
    }

    @Test
    void run_withSpendingTiers_inflationAppliedPerTier() {
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 65, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 55),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("96000.0000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.essentialExpenses()).isEqualByComparingTo(bd("98880.0000"));
    }

    @Test
    void run_withoutSpendingTiers_backwardsCompatible() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), "[]"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("20000.0000"));
    }

    @Test
    void run_withSpendingTiers_nullEndAge_lastTierOpenEnded() {
        var tierJson = tiers()
                .tier("Active", 70, 80, "200000", "74000")
                .tier("Glide", 80, null, "250000", "118000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 82),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("250000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("118000.0000"));
    }

    @Test
    void run_withSpendingTiers_inflationResetsOnTierTransition() {
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 62, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 61),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("96000.0000"));

        var year2 = result.yearlyData().get(1);
        assertThat(year2.essentialExpenses()).isEqualByComparingTo(bd("156000.0000"));
        assertThat(year2.discretionaryExpenses()).isEqualByComparingTo(bd("60000.0000"));

        var year3 = result.yearlyData().get(2);
        assertThat(year3.essentialExpenses()).isEqualByComparingTo(bd("160680.0000"));
    }

    // === Spending Tier Edge Cases ===

    @Test
    void run_withSpendingTiers_snakeCaseJson_parsesCorrectly() {
        var tierJson = tiers()
                .tier("Active", 70, 80, "200000", "74000")
                .buildSnakeCase();

        var input = createInput(
                LocalDate.now().minusYears(1), 85, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 72),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("200000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("74000.0000"));
    }

    @Test
    void run_withSpendingTiers_malformedJson_fallsBackToFlat() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), "not valid json"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("20000.0000"));
    }

    @Test
    void run_withSpendingTiers_gapBetweenTiers_blendsTransitionYear() {
        // Conservation endAge=62 (exclusive, covers 54-61), Go-Go startAge=63
        // Age 62 is a 1-year gap — should blend 50/50
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 63, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 62),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        // Age 62: blend Conservation (96000, 0) + Go-Go (156000, 60000) => (126000, 30000)
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("126000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("30000.0000"));

        // Age 63: fully in Go-Go tier
        var year2 = result.yearlyData().get(1);
        assertThat(year2.essentialExpenses()).isEqualByComparingTo(bd("156000.0000"));
        assertThat(year2.discretionaryExpenses()).isEqualByComparingTo(bd("60000.0000"));
    }

    @Test
    void run_withSpendingTiers_multiYearGap_blendsEachGapYear() {
        // Conservation endAge=62, Go-Go startAge=65 — 3-year gap (62, 63, 64)
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 65, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 62),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        // Ages 62-64: all blend 50/50 between Conservation and Go-Go
        for (int i = 0; i < 3; i++) {
            var year = result.yearlyData().get(i);
            assertThat(year.essentialExpenses()).isEqualByComparingTo(bd("126000.0000"));
            assertThat(year.discretionaryExpenses()).isEqualByComparingTo(bd("30000.0000"));
        }

        // Age 65: fully in Go-Go
        var year4 = result.yearlyData().get(3);
        assertThat(year4.essentialExpenses()).isEqualByComparingTo(bd("156000.0000"));
    }

    @Test
    void run_withSpendingTiers_gapBeforeFirstTier_usesFlat() {
        // Age below all tiers — no previous tier to blend with, so use flat fallback
        var tierJson = tiers()
                .tier("Go-Go", 65, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 60),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        // Age 60: no previous tier, should use flat
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("20000.0000"));
    }

    @Test
    void run_withSpendingTiers_combinedWithIncomeSources_reducesNetNeed() {
        var tierJson = tiers()
                .tier("Active", 65, null, "200000", "50000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 85, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 67),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson),
                List.of(incomeSource("Social Security", "30000", 67, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("200000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("50000.0000"));
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("30000.0000"));
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(bd("220000.0000"));
    }

    @Test
    void run_withSpendingTiers_incomeSourceStartsBeforeTier() {
        var tierJson = tiers()
                .tier("Active", 65, null, "150000", "50000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 63),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("50000"), bd("10000"), tierJson),
                List.of(incomeSource("Pension", "40000", 62, null, "0")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("50000.0000"));
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(bd("20000.0000"));

        var year3 = result.yearlyData().get(2);
        assertThat(year3.essentialExpenses()).isEqualByComparingTo(bd("150000.0000"));
        assertThat(year3.discretionaryExpenses()).isEqualByComparingTo(bd("50000.0000"));
        assertThat(year3.incomeStreamsTotal()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year3.netSpendingNeed()).isEqualByComparingTo(bd("160000.0000"));
    }

    @Test
    void run_withSpendingTiers_multiTierInflationOverManyYears() {
        var tierJson = tiers()
                .tier("Conservation", 60, 65, "100000", "0")
                .tier("Go-Go", 65, null, "200000", "50000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 80, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 60),
                List.of(acct("10000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        assertThat(result.yearlyData().get(0).essentialExpenses())
                .isEqualByComparingTo(bd("100000.0000"));

        // Year 5: age 64, Conservation, 5th year -> $100K * 1.03^4
        var year5 = result.yearlyData().get(4);
        var expected64 = bd("100000").multiply(bd("1.03").pow(4));
        assertThat(year5.essentialExpenses()).isEqualByComparingTo(expected64.setScale(4, RoundingMode.HALF_UP));

        // Year 6: age 65, Go-Go, first year in new tier -> $200K (inflation resets)
        assertThat(result.yearlyData().get(5).essentialExpenses())
                .isEqualByComparingTo(bd("200000.0000"));

        // Year 7: age 66, Go-Go, 2nd year -> $200K * 1.03 = $206K
        assertThat(result.yearlyData().get(6).essentialExpenses())
                .isEqualByComparingTo(bd("206000.0000"));
    }

    @Test
    void run_withSpendingTiers_feasibilityReflectsTierSpending() {
        var tierJson = tiers()
                .tier("Expensive", 60, null, "300000", "100000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 60),
                List.of(acct("500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isFalse();
    }

    @Test
    void run_withSpendingTiers_nullSpendingTiersField_usesFlat() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), null));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("40000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("20000.0000"));
    }

    @Test
    void run_withSpendingTiers_endAgeExclusive_boundaryCorrect() {
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 62, 70, "156000", "60000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - 62),
                List.of(acct("5000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("156000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("60000.0000"));
    }

    // === Parameterized tier age resolution ===

    static Stream<Arguments> tierResolutionCases() {
        return Stream.of(
                // age, expectedEssential, expectedDiscretionary
                Arguments.of(53, "40000.0000", "20000.0000"),     // below all tiers -> flat fallback
                Arguments.of(55, "96000.0000", "0.0000"),         // Conservation (54-62)
                Arguments.of(62, "156000.0000", "60000.0000"),    // Go-Go (62-70, boundary, endAge exclusive)
                Arguments.of(72, "200000.0000", "74000.0000"),    // Active (70-80)
                Arguments.of(85, "250000.0000", "118000.0000")    // Glide (80+, null endAge)
        );
    }

    @ParameterizedTest(name = "age {0} -> essential={1}, discretionary={2}")
    @MethodSource("tierResolutionCases")
    void run_withSpendingTiers_resolvesCorrectTierForAge(int age, String expectedEssential,
                                                           String expectedDiscretionary) {
        var tierJson = tiers()
                .tier("Conservation", 54, 62, "96000", "0")
                .tier("Go-Go", 62, 70, "156000", "60000")
                .tier("Active", 70, 80, "200000", "74000")
                .tier("Glide", 80, null, "250000", "118000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 95, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.10}
                """.formatted(LocalDate.now().getYear() - age),
                List.of(acct("10000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("40000"), bd("20000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd(expectedEssential));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd(expectedDiscretionary));
    }

    // === Spending-needs-driven withdrawal tests ===

    @Test
    void run_withSpendingProfile_withdrawalMatchesSpendingNeed() {
        // $1M balance, 4% rate would give $40k, but spending need = $80k
        // Spending-needs-driven: withdrawal should be $80k, not $40k
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("60000"), bd("20000"), "[]"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("80000.0000"));
    }

    @Test
    void run_withSpendingProfile_portfolioExhausted_capsAtBalance() {
        // $10k balance, $80k need -> withdrawals capped at $10k
        var input = createInput(
                LocalDate.now().minusYears(1), 70, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("10000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("60000"), bd("20000"), "[]"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // After 5% growth: balance = 10500, withdrawal capped at 10500
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("10500.0000"));
        assertThat(year1.endBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withSpendingProfile_tiers_changeWithdrawalByAge() {
        var tierJson = tiers()
                .tier("Frugal", 60, 65, "40000", "10000")
                .tier("Active", 65, null, "80000", "30000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 64),
                List.of(acct("2000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("50000"), bd("15000"), tierJson));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // age 64, Frugal tier: need = 40k + 10k = 50k
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("50000.0000"));

        var year2 = result.yearlyData().get(1);
        // age 65, Active tier: need = 80k + 30k = 110k
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("110000.0000"));
    }

    @Test
    void run_withSpendingProfile_inflationIncreasesWithdrawals() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("2000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("50000"), bd("20000"), "[]"));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("70000.0000"));

        var year2 = result.yearlyData().get(1);
        // Year 2: need = 70k * 1.03 = 72100
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("72100.0000"));
    }

    @Test
    void run_withoutSpendingProfile_strategyStillDrivesWithdrawals() {
        // Backward compatibility: no spending profile, 4% strategy drives withdrawals
        var input = createInput(
                LocalDate.now().minusYears(1), 75, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // 4% of $1M after growth: balance after 5% = $1,050,000 * 0.04 = $42,000
        // Fixed percentage of initial balance: 4% of $1M = $40,000
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));
    }

    @Test
    void run_withSpendingProfile_multiPool_needDistributedAcrossAccounts() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "taxable"),
                        acct("200000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth")),
                new SpendingProfileInput(bd("40000"), bd("20000"), null),
                List.of(incomeSource("Social Security", "10000", 60, null, "0")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        // Spending-needs-driven: need = 60k, income = 10k, portfolio need = 50k
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("50000.0000"));
    }

    // --- Roth Conversion Start Year ---

    @Test
    void run_rothConversionStartYear_skipsConversionBeforeStartYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 60;
        int retirementYear = LocalDate.now().getYear();
        int conversionStartYear = retirementYear + 2;

        var paramsJson = """
                {"birth_year": %d, "annual_roth_conversion": 50000, "filing_status": "single",
                 "roth_conversion_start_year": %d}
                """.formatted(birthYear, conversionStartYear);

        var input = createInput(
                LocalDate.of(retirementYear, 1, 1), 65, bd("0.03"), paramsJson,
                List.of(
                        acct("500000.0000", "0", "0.05", "traditional"),
                        acct("100000.0000", "0", "0.05", "roth")));

        var result = engineTax.run(input);

        // Years before the conversion start year should have no conversion
        var yearsBeforeStart = result.yearlyData().stream()
                .filter(y -> y.year() < conversionStartYear)
                .toList();
        assertThat(yearsBeforeStart).isNotEmpty();
        for (var year : yearsBeforeStart) {
            assertThat(year.rothConversionAmount()).isNull();
        }

        // Years at or after the conversion start year should have conversions
        var yearsAtOrAfterStart = result.yearlyData().stream()
                .filter(y -> y.year() >= conversionStartYear)
                .toList();
        assertThat(yearsAtOrAfterStart).isNotEmpty();
        assertThat(yearsAtOrAfterStart.getFirst().rothConversionAmount()).isNotNull();
    }

    @Test
    void run_rothConversionStartYear_null_convertsFromYear1() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int birthYear = LocalDate.now().getYear() - 60;
        int retirementYear = LocalDate.now().getYear();

        var paramsJson = """
                {"birth_year": %d, "annual_roth_conversion": 50000, "filing_status": "single"}
                """.formatted(birthYear);

        var input = createInput(
                LocalDate.of(retirementYear, 1, 1), 65, bd("0.03"), paramsJson,
                List.of(
                        acct("500000.0000", "0", "0.05", "traditional"),
                        acct("100000.0000", "0", "0.05", "roth")));

        var result = engineTax.run(input);

        // Year 1 should have a conversion (existing behavior preserved)
        assertThat(result.yearlyData().getFirst().rothConversionAmount()).isNotNull();
    }

    // === Feasibility with tiers bug fix ===

    @Test
    void run_withSpendingTiers_feasibility_requiredReflectsTieredSpending() {
        // Base spending: $50k essential + $35k discretionary = $85k
        // Tier reduces to $40k essential + $20k discretionary = $60k for ages 66+
        // Portfolio can sustain ~$70k (between $60k tiered and $85k base)
        // So plan IS feasible, and requiredAnnualSpending should reflect $60k (tiered), not $85k (base)
        var tierJson = tiers()
                .tier("Slow-Go", 66, null, "40000", "20000")
                .build();

        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1500000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("50000"), bd("35000"), tierJson));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
        // requiredAnnualSpending should reflect the tiered amount ($60k), not the base ($85k)
        assertThat(result.spendingFeasibility().requiredAnnualSpending())
                .isLessThanOrEqualTo(bd("60000"));
    }

    // --- Income Source Integration Tests ---

    @Test
    void run_withSocialSecurityIncomeSource_reducesWithdrawals() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 67; // already retired at 67

        var spending = new SpendingProfileInput(bd("40000"), bd("20000"), "[]");

        var ssSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Social Security", "social_security",
                bd("30000"), 67, null, bd("0.02"), false,
                "partially_taxable",
                null, null, null, null, null);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Test Scenario",
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
        // SS provides $30k cash, spending is $60k, so only ~$30k from portfolio
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
                UUID.randomUUID(), "Rental Property", "rental_property",
                bd("24000"), 60, null, BigDecimal.ZERO, false,
                "rental_passive",
                bd("6000"), bd("4000"), bd("3000"),
                "straight_line", depreciationSchedule);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Test Scenario",
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
    void run_withPartTimeWorkAndSETax_computesSelfEmploymentTax() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 63;

        var spending = new SpendingProfileInput(bd("40000"), bd("10000"), "[]");

        var ptSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Consulting", "part_time_work",
                bd("50000"), 60, 70, BigDecimal.ZERO, false,
                "self_employment",
                null, null, null, null, null);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Test Scenario",
                LocalDate.of(currentYear - 1, 1, 1), 80, bd("0.03"),
                """
                {"birth_year": %d}
                """.formatted(birthYear),
                List.of(acct("300000", "0", "0.05")),
                spending, currentYear, List.of(ptSource));

        var result = engine.run(input);
        var year1 = result.yearlyData().getFirst();

        // SE tax should be calculated on $50k
        assertThat(year1.selfEmploymentTax()).isNotNull();
        assertThat(year1.selfEmploymentTax()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_withMultipleIncomeSources_combinesCorrectly() {
        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 68;

        var spending = new SpendingProfileInput(bd("50000"), bd("10000"), "[]");

        var ssSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "SS", "social_security",
                bd("24000"), 67, null, bd("0.02"), false,
                "partially_taxable",
                null, null, null, null, null);

        var pensionSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Pension", "pension",
                bd("20000"), 65, null, BigDecimal.ZERO, false,
                "taxable",
                null, null, null, null, null);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Test Scenario",
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
}
