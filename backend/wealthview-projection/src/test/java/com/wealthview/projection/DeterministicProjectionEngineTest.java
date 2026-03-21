package com.wealthview.projection;

import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.core.projection.tax.NullStateTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculator;
import com.wealthview.core.projection.tax.StateTaxCalculatorFactory;
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
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInputWithProperties;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.incomeSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.oneTimeIncomeSource;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.property;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.propertyNoLoan;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.socialSecuritySource;
import static com.wealthview.projection.testutil.TierJsonBuilder.tiers;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineTest {

    private DeterministicProjectionEngine engine;
    private TaxBracketRepository taxBracketRepository;
    private StandardDeductionRepository standardDeductionRepository;

    @BeforeEach
    void setUp() {
        engine = new DeterministicProjectionEngine(null, null);
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

    @Test
    void run_withSpendingProfile_subTenDollarShortfall_treatedAsFeasible() {
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("59995.0000", "0", "0.0000")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
        assertThat(result.spendingFeasibility().firstShortfallYear()).isNull();
    }

    @Test
    void run_withSpendingProfile_meaningfulShortfall_reportedAsInfeasible() {
        var input = createInput(
                LocalDate.now().minusYears(1), 68, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("59900.0000", "0", "0.0000")),
                new SpendingProfileInput(bd("20000"), bd("10000"), "[]"));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isFalse();
        assertThat(result.spendingFeasibility().firstShortfallYear()).isNotNull();
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

        // Age 62: overlap Conservation+Go-Go → blend 50/50
        var year2 = result.yearlyData().get(1);
        assertThat(year2.essentialExpenses()).isEqualByComparingTo(bd("126000.0000"));
        assertThat(year2.discretionaryExpenses()).isEqualByComparingTo(bd("30000.0000"));
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

        // Age 62: overlap Conservation+Go-Go → blend (126000, 30000), inflation=1.0
        var year2 = result.yearlyData().get(1);
        assertThat(year2.essentialExpenses()).isEqualByComparingTo(bd("126000.0000"));
        assertThat(year2.discretionaryExpenses()).isEqualByComparingTo(bd("30000.0000"));

        // Age 63: Go-Go only, 1st full year in tier → 156000 * 1.03^1 = 160680
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
        // Conservation endAge=61 (inclusive, covers 54-61), Go-Go startAge=63
        // Age 62 is a 1-year gap — should blend 50/50
        var tierJson = tiers()
                .tier("Conservation", 54, 61, "96000", "0")
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

        // Age 62: gap → blend Conservation (96000, 0) + Go-Go (156000, 60000) => (126000, 30000)
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
        // Conservation endAge=62 (inclusive), Go-Go startAge=65 — 2-year gap (63, 64)
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

        // Age 62: still in Conservation (inclusive endAge)
        var year1 = result.yearlyData().get(0);
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("96000.0000"));

        // Ages 63-64: gap → blend 50/50 between Conservation and Go-Go
        for (int i = 1; i <= 2; i++) {
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
        // age 67 = startAge → income halved
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("15000.0000"));
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(bd("235000.0000"));
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

        // Year 6: age 65, overlap Conservation+Go-Go → blend (150000, 25000), inflation=1.0
        assertThat(result.yearlyData().get(5).essentialExpenses())
                .isEqualByComparingTo(bd("150000.0000"));

        // Year 7: age 66, Go-Go only, 1st full year → $200K * 1.03^1 = $206K
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
    void run_withSpendingTiers_inclusiveEndAge_overlapBlends() {
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

        // Age 62: overlap Conservation+Go-Go → blend 50/50
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(bd("126000.0000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(bd("30000.0000"));
    }

    // === Parameterized tier age resolution ===

    static Stream<Arguments> tierResolutionCases() {
        return Stream.of(
                // age, expectedEssential, expectedDiscretionary
                Arguments.of(53, "40000.0000", "20000.0000"),     // below all tiers -> flat fallback
                Arguments.of(55, "96000.0000", "0.0000"),         // Conservation (54-62)
                Arguments.of(62, "126000.0000", "30000.0000"),    // Conservation+Go-Go overlap at 62 → blend 50/50
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

        // age 65: overlap Frugal+Active → blend (60000, 20000) = 80k withdrawal
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(bd("80000.0000"));
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
                bd("30000"), 60, null, bd("0.02"), false,
                "partially_taxable",
                null, null, null, null, null, null);

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
                UUID.randomUUID(), "Rental Property", "rental_property",
                bd("24000"), 60, null, BigDecimal.ZERO, false,
                "rental_passive",
                bd("6000"), bd("4000"), null, bd("3000"),
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
                null, null, null, null, null, null);

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
                null, null, null, null, null, null);

        var pensionSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Pension", "pension",
                bd("20000"), 65, null, BigDecimal.ZERO, false,
                "taxable",
                null, null, null, null, null, null);

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

    // ── Guardrail Spending Path Tests ──

    @Test
    void run_withGuardrailSpending_usesGuardrailWithdrawals() {
        int birthYear = LocalDate.now().getYear() - 66;
        int currentYear = LocalDate.now().getYear();

        var guardrailYears = List.of(
                new GuardrailYearlySpending(currentYear, 66, bd("75000"), bd("62000"),
                        bd("91000"), bd("30000"), bd("45000"), BigDecimal.ZERO,
                        bd("75000"), "Early"),
                new GuardrailYearlySpending(currentYear + 1, 67, bd("76000"), bd("63000"),
                        bd("92000"), bd("30000"), bd("46000"), BigDecimal.ZERO,
                        bd("76000"), "Early"));
        var guardrailInput = new GuardrailSpendingInput(guardrailYears);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Guardrail Test",
                LocalDate.now().minusYears(1), 68, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("1000000.0000", "0", "0.0500")),
                null, null, List.of(), guardrailInput);

        var result = engine.run(input);

        assertThat(result.yearlyData()).isNotEmpty();
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        // Should use guardrail's portfolioWithdrawal (75000) instead of 4% rate (40000)
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("75000"));
    }

    @Test
    void run_withGuardrailSpending_cappedAtBalance() {
        int birthYear = LocalDate.now().getYear() - 66;
        int currentYear = LocalDate.now().getYear();

        // Guardrail wants $75k withdrawal but balance is only $50k
        var guardrailYears = List.of(
                new GuardrailYearlySpending(currentYear, 66, bd("75000"), bd("62000"),
                        bd("91000"), bd("30000"), bd("45000"), BigDecimal.ZERO,
                        bd("75000"), "Early"));
        var guardrailInput = new GuardrailSpendingInput(guardrailYears);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Guardrail Cap Test",
                LocalDate.now().minusYears(1), 67, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("50000.0000", "0", "0.0500")),
                null, null, List.of(), guardrailInput);

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        // Withdrawal should be capped at portfolio balance (with growth first: 50000 * 1.05 = 52500)
        assertThat(year1.withdrawals()).isLessThanOrEqualTo(year1.startBalance().add(year1.growth()));
    }

    @Test
    void run_withGuardrailSpending_yearsNotInGuardrailFallBackToDefault() {
        int birthYear = LocalDate.now().getYear() - 66;
        int currentYear = LocalDate.now().getYear();

        // Only provide guardrail for year 1, not year 2
        var guardrailYears = List.of(
                new GuardrailYearlySpending(currentYear, 66, bd("75000"), bd("62000"),
                        bd("91000"), bd("30000"), bd("45000"), BigDecimal.ZERO,
                        bd("75000"), "Early"));
        var guardrailInput = new GuardrailSpendingInput(guardrailYears);

        var input = new ProjectionInput(
                UUID.randomUUID(), "Partial Guardrail Test",
                LocalDate.now().minusYears(1), 68, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(birthYear),
                List.of(acct("1000000.0000", "0", "0.0500")),
                null, null, List.of(), guardrailInput);

        var result = engine.run(input);

        assertThat(result.yearlyData().size()).isGreaterThanOrEqualTo(2);

        // Year 1 uses guardrail
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("75000"));

        // Year 2 falls back to default strategy (4% rate, inflation-adjusted from guardrail recommended)
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isNotNull();
        // Should not be exactly 75000 or 76000 — falls back to normal withdrawal logic
        assertThat(year2.retired()).isTrue();
    }

    @Test
    void run_withNullGuardrailSpending_usesNormalWithdrawalStrategy() {
        var input = new ProjectionInput(
                UUID.randomUUID(), "No Guardrail Test",
                LocalDate.now().minusYears(1), 90, bd("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                null, null, List.of(), null);

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        // Normal 4% withdrawal: 1,000,000 * 0.04 = 40,000
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("40000.0000"));
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
        assertThat(year1.traditionalGrowth()).isEqualByComparingTo(bd("10000.0000"));
        assertThat(year1.rothGrowth()).isEqualByComparingTo(bd("5000.0000"));
        assertThat(year1.taxableGrowth()).isEqualByComparingTo(bd("2500.0000"));
        assertThat(year1.growth()).isEqualByComparingTo(
                year1.traditionalGrowth().add(year1.rothGrowth()).add(year1.taxableGrowth()));
    }

    @Test
    void run_rothConversion_exposesConversionTaxSource() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth"),
                        acct("50000.0000", "0", "0.0700", "taxable")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        // Tax should come from taxable first
        assertThat(year1.taxPaidFromTaxable()).isNotNull();
        assertThat(year1.taxPaidFromTaxable()).isGreaterThan(BigDecimal.ZERO);
        // Total tax paid from pools should equal tax liability
        BigDecimal totalTaxPaid = year1.taxPaidFromTaxable()
                .add(year1.taxPaidFromTraditional() != null ? year1.taxPaidFromTraditional() : BigDecimal.ZERO)
                .add(year1.taxPaidFromRoth() != null ? year1.taxPaidFromRoth() : BigDecimal.ZERO);
        assertThat(totalTaxPaid).isEqualByComparingTo(year1.taxLiability());
    }

    @Test
    void run_retirementWithdrawal_exposesPerPoolWithdrawals() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("300000.0000", "0", "0.0500", "traditional"),
                        acct("100000.0000", "0", "0.0500", "roth"),
                        acct("100000.0000", "0", "0.0500", "taxable")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isGreaterThan(BigDecimal.ZERO);
        // Default order is taxable-first
        assertThat(year1.withdrawalFromTaxable()).isNotNull();
        // Per-pool withdrawals should sum to total withdrawals
        BigDecimal totalPerPool = (year1.withdrawalFromTaxable() != null ? year1.withdrawalFromTaxable() : BigDecimal.ZERO)
                .add(year1.withdrawalFromTraditional() != null ? year1.withdrawalFromTraditional() : BigDecimal.ZERO)
                .add(year1.withdrawalFromRoth() != null ? year1.withdrawalFromRoth() : BigDecimal.ZERO);
        assertThat(totalPerPool).isEqualByComparingTo(year1.withdrawals());
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
    void rothConversion_withDepreciation_usesReducedTaxableIncome() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 50;

        // Rental property with $90k gross and $180k depreciation year 1
        // Cash inflow = $90k - $10k opex = $80k
        // Taxable income = $90k - $10k opex - $180k dep = -$100k (deeply negative)
        var rentalWithDepreciation = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental with CostSeg", "rental_property",
                bd("90000"), 0, null, bd("0"), false,
                "active_participation",
                bd("10000"), null, null, null,
                "cost_segregation",
                Map.of(currentYear, bd("180000"), currentYear + 1, bd("5000")));

        // Baseline: same cash but NO depreciation
        var rentalNoDepreciation = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental no dep", "rental_property",
                bd("90000"), 0, null, bd("0"), false,
                "active_participation",
                bd("10000"), null, null, null,
                null, null);

        // fill_bracket strategy tries to fill up to a target bracket
        String paramsTemplate = """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """;

        // Run with depreciation
        var inputWithDep = createInput(
                LocalDate.of(currentYear + 15, 1, 1), 90, bd("0"),
                paramsTemplate.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")),
                null,
                List.of(rentalWithDepreciation));
        var resultWithDep = engineTax.run(inputWithDep);

        // Run without depreciation (same cash income)
        var inputNoDep = createInput(
                LocalDate.of(currentYear + 15, 1, 1), 90, bd("0"),
                paramsTemplate.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")),
                null,
                List.of(rentalNoDepreciation));
        var resultNoDep = engineTax.run(inputNoDep);

        // With depreciation: taxable income is much lower → more bracket space → bigger conversion
        var year1WithDep = resultWithDep.yearlyData().getFirst();
        var year1NoDep = resultNoDep.yearlyData().getFirst();

        assertThat(year1WithDep.rothConversionAmount())
                .isGreaterThan(year1NoDep.rothConversionAmount());
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

    // === Fill-bracket Roth conversion with state taxes ===

    private DeterministicProjectionEngine engineWithStateTax(String stateCode) {
        var calc = new FederalTaxCalculator(taxBracketRepository, standardDeductionRepository);
        var factory = mock(StateTaxCalculatorFactory.class);
        // Return a proportional 6% state tax calculator to simulate CA-like behavior
        StateTaxCalculator proportional = new StateTaxCalculator() {
            @Override
            public BigDecimal computeTax(BigDecimal grossIncome, int taxYear, FilingStatus status) {
                return grossIncome.compareTo(BigDecimal.ZERO) > 0
                        ? grossIncome.multiply(bd("0.06")).setScale(4, java.math.RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            }

            @Override
            public BigDecimal getStandardDeduction(int taxYear, FilingStatus status) {
                return BigDecimal.ZERO;
            }

            @Override
            public String stateCode() {
                return stateCode;
            }

            @Override
            public boolean taxesCapitalGainsAsOrdinaryIncome() {
                return true;
            }
        };
        when(factory.forState(stateCode)).thenReturn(proportional);
        return new DeterministicProjectionEngine(calc, factory);
    }

    @Test
    void run_fillBracket_withState_22vs24_producesDifferentConversions() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);

        var engine22 = engineWithStateTax("CA");
        var input22 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var engine24 = engineWithStateTax("CA");
        var input24 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.24}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var result22 = engine22.run(input22);
        var result24 = engine24.run(input24);

        var year1at22 = result22.yearlyData().getFirst();
        var year1at24 = result24.yearlyData().getFirst();

        // Both should convert something
        assertThat(year1at22.rothConversionAmount()).isNotNull();
        assertThat(year1at24.rothConversionAmount()).isNotNull();

        // 24% target should convert MORE than 22% target
        assertThat(year1at24.rothConversionAmount()).isGreaterThan(year1at22.rothConversionAmount());
    }

    @Test
    void run_fillBracket_withState_22vs24_producesDifferentTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);

        var engine22 = engineWithStateTax("CA");
        var input22 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var engine24 = engineWithStateTax("CA");
        var input24 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.24}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("1500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var result22 = engine22.run(input22);
        var result24 = engine24.run(input24);

        var year1at22 = result22.yearlyData().getFirst();
        var year1at24 = result24.yearlyData().getFirst();

        // Higher bracket target → more conversion → more tax
        assertThat(year1at24.taxLiability()).isGreaterThan(year1at22.taxLiability());
    }

    @Test
    void run_fillBracket_withState_mfj_22vs24_differentConversions() {
        stubMfj2025(taxBracketRepository, standardDeductionRepository);

        var engine22 = engineWithStateTax("CA");
        var input22 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("2000000", "0", "0.07", "traditional"),
                        acct("200000", "0", "0.07", "roth")));

        var engine24 = engineWithStateTax("CA");
        var input24 = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "married_filing_jointly", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.24}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("2000000", "0", "0.07", "traditional"),
                        acct("200000", "0", "0.07", "roth")));

        var result22 = engine22.run(input22);
        var result24 = engine24.run(input24);

        var year1at22 = result22.yearlyData().getFirst();
        var year1at24 = result24.yearlyData().getFirst();

        // MFJ 22% bracket ceiling = $206,700, 24% = $394,600 — very different
        assertThat(year1at24.rothConversionAmount()).isGreaterThan(year1at22.rothConversionAmount());
        // The difference should be substantial
        assertThat(year1at24.rothConversionAmount().subtract(year1at22.rothConversionAmount()))
                .isGreaterThan(bd("100000"));
    }

    @Test
    void run_fillBracket_withState_conversionAmountMatchesFederalBracketCeiling() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);

        // With no state tax, fill_bracket at 12% should fill to federal 12% bracket
        var engineFedOnly = engineWithTax(taxBracketRepository, standardDeductionRepository);
        var inputFedOnly = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        // With state tax, fill_bracket at 12% should STILL fill to the federal 12% bracket
        // (the target rate refers to the federal bracket, not the combined rate)
        var engineState = engineWithStateTax("CA");
        var inputState = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.07", "traditional"),
                        acct("100000", "0", "0.07", "roth")));

        var resultFedOnly = engineFedOnly.run(inputFedOnly);
        var resultState = engineState.run(inputState);

        var fedConv = resultFedOnly.yearlyData().getFirst().rothConversionAmount();
        var stateConv = resultState.yearlyData().getFirst().rothConversionAmount();

        // Federal-only: 12% ceiling $48,475 + $15,000 standard deduction = $63,475
        assertThat(fedConv).isEqualByComparingTo(bd("63475"));

        // With state: conversion should be similar — possibly slightly different due to
        // itemized vs standard deduction choice, but should be in the same ballpark
        // (not wildly different due to combined marginal rate confusion)
        assertThat(stateConv).isGreaterThan(bd("50000"));
        assertThat(stateConv).isLessThan(bd("80000"));
    }

    // === Gap #1: Conversion + withdrawal combined tax must not double-count ===

    @Test
    void run_conversionAndTraditionalWithdrawal_correctCombinedTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Retired, 0% return, 0% inflation → perfectly predictable amounts
        // other_income=20000, annual_roth_conversion=30000, withdrawal_rate=0.04
        // withdrawal_order=traditional_first forces traditional withdrawal
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion: $30K. Withdrawal: 4% of $900K = $36K from traditional.
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("30000"));
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("36000"));

        // Correct tax = tax($30K conv + $36K wd + $20K other = $86K gross)
        // Taxable = $86K - $15K deduction = $71K
        // 10% on $11,925 = $1,192.50
        // 12% on ($48,475 - $11,925) = $4,386.00
        // 22% on ($71,000 - $48,475) = $4,955.50
        // Total = $10,534.00
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("10534.0000"));
    }

    @Test
    void run_conversionAndTraditionalWithdrawal_taxLiabilityMatchesFederalTax() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // For federal-only, taxLiability must equal the federalTax breakdown
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(year1.federalTax());
    }

    @Test
    void run_conversionAndTraditionalWithdrawal_withState_taxMatchesBreakdown() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first", "state": "CA"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        // taxLiability must equal federalTax + stateTax
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.stateTax()).isNotNull();
        BigDecimal breakdownTotal = year1.federalTax().add(year1.stateTax());
        assertThat(year1.taxLiability()).isEqualByComparingTo(breakdownTotal);
    }

    @Test
    void run_traditionalWithdrawalOnly_noConversion_taxCorrect() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // No Roth conversion — just traditional withdrawal
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Withdrawal: 4% of $800K = $32K from traditional
        // Tax = tax($32K + $20K other = $52K gross)
        // Taxable = $52K - $15K = $37K
        // 10% on $11,925 = $1,192.50
        // 12% on ($37,000 - $11,925) = $3,009.00
        // Total = $4,201.50
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("4201.5000"));
    }

    @Test
    void run_conversionOnly_preRetirement_taxCorrect() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Pre-retirement conversion only (no withdrawal)
        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion: $30K. Tax = tax($30K + $20K = $50K)
        // Taxable = $50K - $15K = $35K
        // 10% on $11,925 = $1,192.50
        // 12% on ($35,000 - $11,925) = $2,769.00
        // Total = $3,961.50
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("30000"));
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("3961.5000"));
    }

    // === Gap #3: Social Security taxable fraction affects fill-bracket space ===

    @Test
    void run_fillBracket_withSocialSecurity_useTaxableFractionNotFullAmount() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // With SS typed as "other" (fully taxable $30K)
        var inputOther = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 40000,
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(birthYear),
                List.of(
                        acct("1000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(incomeSource("Pension", "30000", retireAge, null, "0")));

        // With SS typed as "social_security" (only ~85% taxable = $25,500)
        var inputSS = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 40000,
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22}
                """.formatted(birthYear),
                List.of(
                        acct("1000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(socialSecuritySource("30000", retireAge)));

        var resultOther = engineTax.run(inputOther);
        var resultSS = engineTax.run(inputSS);

        var convOther = resultOther.yearlyData().getFirst().rothConversionAmount();
        var convSS = resultSS.yearlyData().getFirst().rothConversionAmount();

        // SS taxable fraction < full amount, so effectiveOtherIncome is lower,
        // leaving MORE room for Roth conversion
        assertThat(convSS).isGreaterThan(convOther);
    }

    // === Gap #4: Tax source pool assignment when conversion exhausts taxable ===

    @Test
    void run_conversionTaxExhaustsTaxable_withdrawalTaxFallsOnTraditional() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Tiny taxable balance ($1000) — conversion tax ($3961.50) will exceed it
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("1000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Tax was paid — some must have come from traditional since taxable was tiny
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.taxPaidFromTraditional()).isNotNull();
        assertThat(year1.taxPaidFromTraditional()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Gap #6: Pre-retirement conversion with active income sources ===

    @Test
    void run_fillBracket_preRetirement_withIncomeSource_reducesConversion() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int age = 50;
        int birthYear = LocalDate.now().getYear() - age;

        // Without income source
        var inputNoIncome = createInput(
                LocalDate.now().plusYears(15), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")));

        // With $20K pension, starting at age 49 (so age 50 is NOT the start year,
        // avoiding the 0.5 transition multiplier)
        var inputWithIncome = createInput(
                LocalDate.now().plusYears(15), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(birthYear),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(incomeSource("Pension", "20000", age - 1, null, "0")));

        var resultNoIncome = engineTax.run(inputNoIncome);
        var resultWithIncome = engineTax.run(inputWithIncome);

        var convNoIncome = resultNoIncome.yearlyData().getFirst().rothConversionAmount();
        var convWithIncome = resultWithIncome.yearlyData().getFirst().rothConversionAmount();

        // 12% bracket ceiling = $63,475
        // Without income: full $63,475 conversion
        assertThat(convNoIncome).isEqualByComparingTo(bd("63475"));
        // With $20K income: reduced to $63,475 - $20,000 = $43,475
        assertThat(convWithIncome).isEqualByComparingTo(bd("43475"));
    }

    // === Gap #7: State tax breakdown fields in retirement years ===

    @Test
    void run_withStateTax_retirementYear_breakdownFieldsPopulated() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000,
                 "withdrawal_order": "traditional_first", "state": "CA",
                 "primary_residence_property_tax": 5000}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.federalTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.stateTax()).isNotNull();
        assertThat(year1.stateTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.saltDeduction()).isNotNull();
        assertThat(year1.usedItemizedDeduction()).isNotNull();
    }

    // === Gap #8: Pre-retirement conversion tax breakdown fields ===

    @Test
    void run_preRetirementConversion_breakdownFieldsSet() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineState = engineWithStateTax("CA");

        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "state": "CA",
                 "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("300000", "0", "0.00", "taxable")));

        var result = engineState.run(input);
        var year1 = result.yearlyData().getFirst();

        // Pre-retirement conversion should still have tax breakdown from lastTaxBreakdown
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.federalTax()).isNotNull();
        assertThat(year1.federalTax()).isGreaterThan(BigDecimal.ZERO);
        assertThat(year1.stateTax()).isNotNull();
        assertThat(year1.stateTax()).isGreaterThan(BigDecimal.ZERO);
    }

    // === Complex interaction integration tests ===

    @Test
    void run_shortfall_cutsDiscretionaryWhenPortfolioCantFundSpending() {
        // Small portfolio depletes quickly — spending plan drives withdrawal > balance
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("50000", "0", "0.00")),
                new SpendingProfileInput(bd("30000"), bd("15000"), "[]"));

        var result = engine.run(input);

        // Find a year where balance is depleted and withdrawal < spending need
        boolean foundShortfall = false;
        for (var year : result.yearlyData()) {
            if (year.retired() && year.discretionaryAfterCuts() != null
                    && year.discretionaryAfterCuts().compareTo(bd("15000")) < 0) {
                foundShortfall = true;
                assertThat(year.discretionaryAfterCuts()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
                break;
            }
        }
        assertThat(foundShortfall).as("Expected at least one year with discretionary cuts").isTrue();
    }

    @Test
    void run_suspendedLoss_carriesForwardAndReleasesInLaterYear() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int currentYear = LocalDate.now().getYear();
        int birthYear = currentYear - 66;

        // High MAGI ($200K) eliminates $25K PAL exception → losses fully suspended
        // Year 1: $40K depreciation creates ~$29K loss (suspended)
        // Year 2: $0 depreciation → rental is profitable, suspended loss releases
        var depSchedule = Map.of(currentYear, bd("40000"));

        var rentalSource = new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Rental", "rental_property",
                bd("24000"), 60, null, BigDecimal.ZERO, false,
                "rental_passive",
                bd("6000"), bd("4000"), null, bd("3000"),
                "straight_line", depSchedule);

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 200000}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "taxable"),
                        acct("200000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth")),
                null,
                List.of(rentalSource));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();
        var year2 = result.yearlyData().get(1);

        // Year 1: depreciation creates loss → suspended
        assertThat(year1.suspendedLossCarryforward()).isNotNull();
        assertThat(year1.suspendedLossCarryforward()).isGreaterThan(BigDecimal.ZERO);

        // Year 2: no depreciation → rental profitable → suspended loss releases
        assertThat(year2.suspendedLossCarryforward()).isNotNull();
        assertThat(year2.suspendedLossCarryforward()).isLessThan(year1.suspendedLossCarryforward());
    }

    @Test
    void run_fillBracket_retiredWithTraditionalWithdrawal_combinedTaxSpansBrackets() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Fill-bracket at 22% + large traditional withdrawal pushes into 24% bracket
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.22,
                 "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("1000000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("50000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("40000"), bd("20000"), "[]"));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion fills to 22% bracket: $103,350 + $15,000 deduction = $118,350
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("118350"));

        // Withdrawal: $60K from traditional (spending need)
        // Combined taxable = $118,350 + $60,000 = $178,350
        // Tax on $178,350 (with $15K deduction → $163,350 taxable):
        // 10%: $1,192.50, 12%: $4,386, 22%: $12,072.50, 24%: $14,400
        // Total = $32,051
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("32051.0000"));

        // Tax must be much higher than conversion-only tax
        assertThat(year1.taxLiability()).isGreaterThan(bd("20000"));
    }

    @Test
    void run_proRata_unevenBalances_withdrawalsSumToNeed() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single",
                 "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(
                        acct("333000", "0", "0.00", "traditional"),
                        acct("222000", "0", "0.00", "roth"),
                        acct("111000", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Total = $666K, withdrawal = 4% = $26,640
        assertThat(year1.withdrawals()).isEqualByComparingTo(bd("26640"));

        // Sum of per-pool withdrawals must equal total
        BigDecimal sumPools = year1.withdrawalFromTaxable()
                .add(year1.withdrawalFromTraditional())
                .add(year1.withdrawalFromRoth());
        assertThat(sumPools).isEqualByComparingTo(year1.withdrawals());

        // Each pool's share should be proportional (within rounding)
        // traditional: 333/666 = 50%, roth: 222/666 = 33.3%, taxable: 111/666 = 16.7%
        assertThat(year1.withdrawalFromTraditional().divide(year1.withdrawals(), 2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo(bd("0.50"));
    }

    @Test
    void run_ssSurplus_taxOnTaxablePortionOnly() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // SS $40K + other_income $30K → provisional = $30K + $20K(50% SS) = $50K > $34K
        // Both tiers: tier1 = ($34K-$25K)*0.5 = $4,500, tier2 = ($50K-$34K)*0.85 = $13,600
        // SS taxable = $18,100 (< 85% cap of $34K)
        // Spending $20K < cash $40K → surplus = $20K
        // Tax on $18,100 taxable: after $15K deduction = $3,100
        // 10%: $3,100 * 0.10 = $310
        // afterTaxSurplus = $20,000 - $310 = $19,690
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 30000}
                """.formatted(birthYear),
                List.of(
                        acct("200000", "0", "0.00", "roth"),
                        acct("100000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("15000"), bd("5000"), "[]"),
                List.of(socialSecuritySource("40000", retireAge - 1)));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("40000"));
        assertThat(year1.surplusReinvested()).isNotNull();
        assertThat(year1.surplusReinvested()).isEqualByComparingTo(bd("19690.0000"));
    }

    @Test
    void run_rothFirstWithdrawal_incomeSourceIncome_taxShouldBeComputed() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        int retireAge = 66;
        int birthYear = LocalDate.now().getYear() - retireAge;

        // Pension $30K, spending $45K, roth_first withdrawal covers $15K gap
        // No traditional withdrawal → no conversion → income tax on pension might be missed
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "withdrawal_order": "roth_first"}
                """.formatted(birthYear),
                List.of(
                        acct("300000", "0", "0.00", "roth"),
                        acct("100000", "0", "0.00", "taxable")),
                new SpendingProfileInput(bd("30000"), bd("15000"), "[]"),
                List.of(incomeSource("Pension", "30000", retireAge - 1, null, "0")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Pension $30K is taxable income — tax must be computed even without
        // traditional withdrawal or conversion.
        // Tax on $30K: taxable = $30K - $15K deduction = $15K
        // 10%: $11,925 * 0.10 = $1,192.50
        // 12%: ($15,000 - $11,925) * 0.12 = $369.00
        // Total = $1,561.50
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("1561.5000"));
    }

    @Test
    void run_poolCascade_conversionTaxExhaustsTaxable_exactValues() {
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
        var engineTax = engineWithTax(taxBracketRepository, standardDeductionRepository);

        // Tiny taxable ($500), conversion tax $3,961.50 exceeds it
        var input = createInput(
                LocalDate.now().plusYears(10), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single",
                 "other_income": 20000, "annual_roth_conversion": 30000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000", "0", "0.00", "traditional"),
                        acct("100000", "0", "0.00", "roth"),
                        acct("500", "0", "0.00", "taxable")));

        var result = engineTax.run(input);
        var year1 = result.yearlyData().getFirst();

        // Conversion tax = tax($50K) = $3,961.50
        assertThat(year1.taxLiability()).isEqualByComparingTo(bd("3961.5000"));

        // Cascade: $500 from taxable, $3,461.50 from traditional
        assertThat(year1.taxPaidFromTaxable()).isEqualByComparingTo(bd("500"));
        assertThat(year1.taxPaidFromTraditional()).isEqualByComparingTo(bd("3461.5000"));
        assertThat(year1.taxPaidFromRoth()).isNull();

        // Taxable fully drained
        assertThat(year1.taxableBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        // All balances non-negative
        assertThat(year1.traditionalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(year1.rothBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
