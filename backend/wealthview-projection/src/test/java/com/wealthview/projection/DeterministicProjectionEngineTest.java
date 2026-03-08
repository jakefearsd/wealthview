package com.wealthview.projection;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineTest {

    private DeterministicProjectionEngine engine;
    private TaxBracketRepository taxBracketRepository;

    @BeforeEach
    void setUp() {
        engine = new DeterministicProjectionEngine(null);
        taxBracketRepository = mock(TaxBracketRepository.class);
    }

    private DeterministicProjectionEngine engineWithTax() {
        var calc = new FederalTaxCalculator(taxBracketRepository);
        return new DeterministicProjectionEngine(calc);
    }

    private void stubSingleBrackets() {
        lenient().when(taxBracketRepository.findByTaxYearAndFilingStatusOrderByBracketFloorAsc(anyInt(), eq("single")))
                .thenReturn(List.of(
                        new TaxBracketEntity(2025, "single", bd("0"), bd("11925"), bd("0.1000")),
                        new TaxBracketEntity(2025, "single", bd("11925"), bd("48475"), bd("0.1200")),
                        new TaxBracketEntity(2025, "single", bd("48475"), bd("103350"), bd("0.2200")),
                        new TaxBracketEntity(2025, "single", bd("103350"), bd("197300"), bd("0.2400")),
                        new TaxBracketEntity(2025, "single", bd("197300"), bd("250525"), bd("0.3200")),
                        new TaxBracketEntity(2025, "single", bd("250525"), bd("626350"), bd("0.3500")),
                        new TaxBracketEntity(2025, "single", bd("626350"), null, bd("0.3700"))));
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }

    private ProjectionInput createInput(LocalDate retDate, int endAge,
            BigDecimal inflation, String paramsJson, List<ProjectionAccountInput> accounts) {
        return createInput(retDate, endAge, inflation, paramsJson, accounts, null);
    }

    private ProjectionInput createInput(LocalDate retDate, int endAge,
            BigDecimal inflation, String paramsJson, List<ProjectionAccountInput> accounts,
            SpendingProfileInput spendingProfile) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, spendingProfile);
    }

    private HypotheticalAccountInput acct(String balance, String contribution, String expectedReturn) {
        return new HypotheticalAccountInput(bd(balance), bd(contribution), bd(expectedReturn), "taxable");
    }

    private HypotheticalAccountInput acct(String balance, String contribution, String expectedReturn, String type) {
        return new HypotheticalAccountInput(bd(balance), bd(contribution), bd(expectedReturn), type);
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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        assertThat(year1.spendingSurplus()).isEqualByComparingTo(bd("-5000.0000"));
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
        assertThat(year1.discretionaryAfterCuts()).isEqualByComparingTo(bd("10000.0000"));
    }

    @Test
    void run_withSpendingProfile_incomeStreamReducesNeed() {
        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"),
                        """
                        [{"name":"Social Security","annualAmount":20000,"startAge":60,"endAge":null}]
                        """));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(bd("25000"));
    }

    @Test
    void run_withSpendingProfile_incomeStreamStartsLater() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"),
                        """
                        [{"name":"Social Security","annualAmount":24000,"startAge":67,"endAge":null}]
                        """));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("24000"));
    }

    @Test
    void run_withSpendingProfile_incomeStreamEndsAtEndAge() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("15000"),
                        """
                        [{"name":"Part-time","annualAmount":30000,"startAge":66,"endAge":68}]
                        """));

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_fillBracketStrategy_convertsToFillBracket() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("48475"));
    }

    @Test
    void run_fillBracketStrategy_withOtherIncome_reducesConversion() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

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
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("18475"));
    }

    @Test
    void run_fillBracketStrategy_traditionalExhausted_convertsOnlyAvailable() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("10000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("10700.0000"));
    }

    @Test
    void run_fixedAmountStrategy_backwardsCompatible() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var input = createInput(
                LocalDate.now().plusYears(30), 90, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 25000}
                """.formatted(LocalDate.now().getYear() - 35),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engineTax.run(input);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("25000"));
    }

    @Test
    void run_noWithdrawalOrderParam_defaultsTaxableFirst() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var input = createInput(
                LocalDate.now().minusYears(1), 75, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
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
                new SpendingProfileInput(bd("30000"), bd("10000"),
                        """
                        [{"name":"Social Security","annualAmount":20000,"startAge":60,"endAge":null,"inflationRate":0.02}]
                        """));

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
                new SpendingProfileInput(bd("30000"), bd("10000"),
                        """
                        [{"name":"Social Security","annualAmount":20000,"startAge":60,"endAge":null}]
                        """));

        var result = engine.run(input);

        var year1 = result.yearlyData().getFirst();
        var year2 = result.yearlyData().get(1);
        var year3 = result.yearlyData().get(2);
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));
        assertThat(year3.incomeStreamsTotal()).isEqualByComparingTo(bd("20000"));
    }

    @Test
    void run_withSpendingProfile_perStreamDifferentRates_inflatesIndependently() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66),
                List.of(acct("1000000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("30000"), bd("10000"),
                        """
                        [{"name":"Social Security","annualAmount":20000,"startAge":60,"endAge":null,"inflationRate":0.02},
                         {"name":"Rental Income","annualAmount":10000,"startAge":60,"endAge":null,"inflationRate":0.03}]
                        """));

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
                new SpendingProfileInput(bd("20000"), bd("10000"),
                        """
                        [{"name":"Social Security","annualAmount":40000,"startAge":60,"endAge":null}]
                        """));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isTrue();
    }

    @Test
    void run_withSpendingProfile_delayedIncome_shortfallInGapYears() {
        var input = createInput(
                LocalDate.now().minusYears(1), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 65),
                List.of(acct("100000.0000", "0", "0.0500")),
                new SpendingProfileInput(bd("25000"), bd("15000"),
                        """
                        [{"name":"Social Security","annualAmount":30000,"startAge":67,"endAge":null}]
                        """));

        var result = engine.run(input);

        assertThat(result.spendingFeasibility()).isNotNull();
        assertThat(result.spendingFeasibility().spendingFeasible()).isFalse();
        assertThat(result.spendingFeasibility().firstShortfallAge()).isEqualTo(65);
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
}
