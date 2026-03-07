package com.wealthview.projection;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.FilingStatus;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeterministicProjectionEngineTest {

    private DeterministicProjectionEngine engine;
    private TenantEntity tenant;
    private TaxBracketRepository taxBracketRepository;

    @BeforeEach
    void setUp() {
        engine = new DeterministicProjectionEngine(null);
        tenant = new TenantEntity("Test");
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

    @Test
    void run_singleAccountPreRetirement_growsWithContributions() {
        // 35-year-old, retires at 65 (in 30 years), end age 90
        // $100,000 initial, $10,000/year contribution, 7% return, 3% inflation
        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                new BigDecimal("0.0300"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                new BigDecimal("10000.0000"),
                new BigDecimal("0.0700"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        assertThat(result.scenarioId()).isEqualTo(scenario.getId());
        assertThat(result.yearlyData()).isNotEmpty();

        // First year: start 100k, contribute 10k, grow by 7%
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(year1.contributions()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(year1.retired()).isFalse();
        // Growth = (100000 + 10000) * 0.07 = 7700
        assertThat(year1.growth()).isEqualByComparingTo(new BigDecimal("7700.0000"));
        assertThat(year1.endBalance()).isEqualByComparingTo(new BigDecimal("117700.0000"));

        // Should have years from now until end age
        assertThat(result.yearlyData()).hasSizeGreaterThan(30);
        assertThat(result.yearsInRetirement()).isGreaterThan(0);
    }

    @Test
    void run_postRetirement_withdrawsAndAdjustsForInflation() {
        // Already retired (retirement date in the past), end age 90
        // $1,000,000 initial, no contributions, 5% return, 3% inflation, 4% withdrawal
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                90,
                new BigDecimal("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        assertThat(result.yearlyData()).isNotEmpty();

        // First year: retired, withdrawals should be 4% of initial = 40,000
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        assertThat(year1.contributions()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.withdrawals()).isEqualByComparingTo(new BigDecimal("40000.0000"));

        // Second year: withdrawal should be inflation-adjusted = 40000 * 1.03 = 41200
        if (result.yearlyData().size() > 1) {
            var year2 = result.yearlyData().get(1);
            assertThat(year2.withdrawals()).isEqualByComparingTo(new BigDecimal("41200.0000"));
        }

        assertThat(result.yearsInRetirement()).isGreaterThan(0);
    }

    @Test
    void run_multipleAccounts_aggregatesCorrectly() {
        // Two accounts with different returns
        var scenario = createScenario(
                LocalDate.now().plusYears(20),
                80,
                new BigDecimal("0.0200"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 40));

        var account1 = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("200000.0000"),
                new BigDecimal("5000.0000"),
                new BigDecimal("0.0800"));
        scenario.addAccount(account1);

        var account2 = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                new BigDecimal("3000.0000"),
                new BigDecimal("0.0400"));
        scenario.addAccount(account2);

        var result = engine.run(scenario);

        // First year start balance = 200000 + 100000 = 300000
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(new BigDecimal("300000"));
        assertThat(year1.contributions()).isEqualByComparingTo(new BigDecimal("8000"));

        // Weighted return: (200000*0.08 + 100000*0.04) / 300000 = 20000/300000 = 0.066667
        // Growth = (300000 + 8000) * 0.066667 = 20533.33 (approx)
        assertThat(year1.growth().setScale(0, RoundingMode.HALF_UP))
                .isEqualByComparingTo(new BigDecimal("20533"));
    }

    @Test
    void run_balanceReachesZero_stopsAtZero() {
        // High withdrawal rate, low return — balance will deplete
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                95,
                new BigDecimal("0.0200"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.20}
                """.formatted(LocalDate.now().getYear() - 70));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0300"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        // Balance should eventually reach zero
        var lastYear = result.yearlyData().getLast();
        assertThat(lastYear.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // At least some years should show the balance declining
        var balanceDeclined = result.yearlyData().stream()
                .anyMatch(y -> y.endBalance().compareTo(y.startBalance()) < 0);
        assertThat(balanceDeclined).isTrue();

        assertThat(result.finalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void run_zeroReturn_onlyContributionsAndWithdrawals() {
        var scenario = createScenario(
                LocalDate.now().plusYears(10),
                70,
                BigDecimal.ZERO,
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 30));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("50000.0000"),
                new BigDecimal("5000.0000"),
                BigDecimal.ZERO);
        scenario.addAccount(account);

        var result = engine.run(scenario);

        // First year: no growth, just contributions
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.startBalance()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(year1.contributions()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(year1.growth()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.endBalance()).isEqualByComparingTo(new BigDecimal("55000"));
    }

    @Test
    void run_dynamicPercentageStrategy_withdrawsPercentOfCurrentBalance() {
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                90,
                new BigDecimal("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "dynamic_percentage"}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        // Year 1: growth first, then withdrawal = 4% of current balance (after growth)
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.retired()).isTrue();
        // Balance after growth = 1000000 * 1.05 = 1050000
        // Withdrawal = 1050000 * 0.04 = 42000
        assertThat(year1.withdrawals()).isEqualByComparingTo(new BigDecimal("42000.0000"));

        // Year 2: dynamic should be % of current balance, not inflation-adjusted
        var year2 = result.yearlyData().get(1);
        // End balance year 1 = 1050000 - 42000 = 1008000
        // After growth year 2 = 1008000 * 1.05 = 1058400
        // Withdrawal = 1058400 * 0.04 = 42336
        assertThat(year2.withdrawals()).isEqualByComparingTo(new BigDecimal("42336.0000"));
    }

    @Test
    void run_vanguardStrategy_capsIncreasesAndFloorsDecreases() {
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                80,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "vanguard_dynamic_spending", "dynamic_ceiling": 0.05, "dynamic_floor": -0.025}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        // Year 1: 4% of balance after growth = 1050000 * 0.04 = 42000
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isEqualByComparingTo(new BigDecimal("42000.0000"));

        // Year 2: balance grew, so raw withdrawal would increase
        // But it should be capped at +5% of previous = 42000 * 1.05 = 44100
        // Actual raw = (1050000-42000)*1.05 * 0.04 = 1058400*0.04 = 42336
        // 42336 < 44100 cap, so not capped
        var year2 = result.yearlyData().get(1);
        assertThat(year2.withdrawals()).isEqualByComparingTo(new BigDecimal("42336.0000"));
    }

    @Test
    void run_noStrategySpecified_defaultsToFixedPercentage() {
        // Existing behavior: no withdrawal_strategy field defaults to fixed_percentage
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                90,
                new BigDecimal("0.0300"),
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // Fixed percentage: first year = startBalance * rate = 1000000 * 0.04 = 40000
        assertThat(year1.withdrawals()).isEqualByComparingTo(new BigDecimal("40000.0000"));

        // Second year: inflation-adjusted = 40000 * 1.03 = 41200
        if (result.yearlyData().size() > 1) {
            var year2 = result.yearlyData().get(1);
            assertThat(year2.withdrawals()).isEqualByComparingTo(new BigDecimal("41200.0000"));
        }
    }

    @Test
    void run_multipleAccountTypes_tracksPoolsSeparately() {
        var scenario = createScenario(
                LocalDate.now().plusYears(5),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("200000.0000"),
                new BigDecimal("10000.0000"),
                new BigDecimal("0.0700"),
                "traditional");
        scenario.addAccount(tradAcct);

        var rothAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                new BigDecimal("5000.0000"),
                new BigDecimal("0.0700"),
                "roth");
        scenario.addAccount(rothAcct);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.traditionalBalance()).isNotNull();
        assertThat(year1.rothBalance()).isNotNull();
        assertThat(year1.taxableBalance()).isNotNull();
        // Traditional should be larger than roth
        assertThat(year1.traditionalBalance()).isGreaterThan(year1.rothBalance());
    }

    @Test
    void run_rothConversion_movesFromTraditionalToRoth() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 50000}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("500000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0700"),
                "traditional");
        scenario.addAccount(tradAcct);

        var rothAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0700"),
                "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(new BigDecimal("50000"));
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_rothConversionExceedsTraditionalBalance_convertsOnlyAvailable() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 500000}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("30000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0700"),
                "traditional");
        scenario.addAccount(tradAcct);

        var rothAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0700"),
                "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        // Year 1: traditional grows from 30000 to 32100, then conversion = min(500000, 32100) = 32100
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isLessThanOrEqualTo(new BigDecimal("32100.0001"));
    }

    @Test
    void run_noAccountTypes_backwardsCompatible() {
        // All accounts default to "taxable", should behave like legacy single-pool
        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                new BigDecimal("0.0300"),
                """
                {"birth_year": %d}
                """.formatted(LocalDate.now().getYear() - 35));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("100000.0000"),
                new BigDecimal("10000.0000"),
                new BigDecimal("0.0700"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // No pool data for single-pool legacy mode
        assertThat(year1.traditionalBalance()).isNull();
        assertThat(year1.rothBalance()).isNull();
        assertThat(year1.taxableBalance()).isNull();
    }

    @Test
    void run_allRothPortfolio_noTaxOnWithdrawals() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66));

        var rothAcct = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("500000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"),
                "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        // All Roth — no tax liability on withdrawals (no traditional withdrawals)
        for (var yearData : result.yearlyData()) {
            if (yearData.taxLiability() != null) {
                assertThat(yearData.taxLiability()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    @Test
    void run_withSpendingProfile_computesViabilityFields() {
        // Already retired, $1M, 4% withdrawal = $40k first year
        // Essential: $30k, Discretionary: $15k, no income streams
        // Net need = 45k, withdrawal = 40k (after growth), surplus = withdrawal - 45k
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var profile = new SpendingProfileEntity(
                tenant, "Test Profile",
                new BigDecimal("30000"), new BigDecimal("15000"), "[]");
        scenario.setSpendingProfile(profile);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.essentialExpenses()).isNotNull();
        assertThat(year1.essentialExpenses()).isEqualByComparingTo(new BigDecimal("30000"));
        assertThat(year1.discretionaryExpenses()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(new BigDecimal("45000"));
        // Withdrawal = 40000 (fixed 4% of start), surplus = 40000 - 45000 = -5000
        assertThat(year1.spendingSurplus()).isEqualByComparingTo(new BigDecimal("-5000.0000"));
    }

    @Test
    void run_withSpendingProfile_shortfallCutsDiscretionary() {
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        // Essential: 30k, Discretionary: 15k, Withdrawal: 40k
        // Shortfall = 45k - 40k = 5k => discretionary after cuts = 15k - 5k = 10k
        var profile = new SpendingProfileEntity(
                tenant, "Test Profile",
                new BigDecimal("30000"), new BigDecimal("15000"), "[]");
        scenario.setSpendingProfile(profile);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.discretionaryAfterCuts()).isEqualByComparingTo(new BigDecimal("10000.0000"));
    }

    @Test
    void run_withSpendingProfile_incomeStreamReducesNeed() {
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        // Essential: 30k, Discretionary: 15k, Income: 20k (active at age 66)
        // Net need = 45k - 20k = 25k
        var profile = new SpendingProfileEntity(
                tenant, "Test Profile",
                new BigDecimal("30000"), new BigDecimal("15000"),
                """
                [{"name":"Social Security","annualAmount":20000,"startAge":60,"endAge":null}]
                """);
        scenario.setSpendingProfile(profile);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(new BigDecimal("20000"));
        assertThat(year1.netSpendingNeed()).isEqualByComparingTo(new BigDecimal("25000"));
    }

    @Test
    void run_withSpendingProfile_incomeStreamStartsLater() {
        // Age 66, SS starts at 67
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                80,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var profile = new SpendingProfileEntity(
                tenant, "Test Profile",
                new BigDecimal("30000"), new BigDecimal("15000"),
                """
                [{"name":"Social Security","annualAmount":24000,"startAge":67,"endAge":null}]
                """);
        scenario.setSpendingProfile(profile);

        var result = engine.run(scenario);

        // Year 1 (age 66): SS not yet active
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.incomeStreamsTotal()).isEqualByComparingTo(BigDecimal.ZERO);

        // Year 2 (age 67): SS active
        var year2 = result.yearlyData().get(1);
        assertThat(year2.incomeStreamsTotal()).isEqualByComparingTo(new BigDecimal("24000"));
    }

    @Test
    void run_withSpendingProfile_incomeStreamEndsAtEndAge() {
        // Part-time work from 66 to 68 (endAge=68, so active at 66 and 67, not 68)
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                80,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var profile = new SpendingProfileEntity(
                tenant, "Test Profile",
                new BigDecimal("30000"), new BigDecimal("15000"),
                """
                [{"name":"Part-time","annualAmount":30000,"startAge":66,"endAge":68}]
                """);
        scenario.setSpendingProfile(profile);

        var result = engine.run(scenario);

        // Year 1 (age 66): active
        assertThat(result.yearlyData().getFirst().incomeStreamsTotal())
                .isEqualByComparingTo(new BigDecimal("30000"));
        // Year 2 (age 67): active
        assertThat(result.yearlyData().get(1).incomeStreamsTotal())
                .isEqualByComparingTo(new BigDecimal("30000"));
        // Year 3 (age 68): ended
        assertThat(result.yearlyData().get(2).incomeStreamsTotal())
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void run_withoutSpendingProfile_viabilityFieldsNull() {
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04}
                """.formatted(LocalDate.now().getYear() - 66));

        var account = new ProjectionAccountEntity(
                scenario, null,
                new BigDecimal("1000000.0000"),
                BigDecimal.ZERO,
                new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

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

        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "taxable_first"}
                """.formatted(LocalDate.now().getYear() - 66));

        var taxableAcct = new ProjectionAccountEntity(
                scenario, null, bd("300000.0000"), BigDecimal.ZERO, bd("0.0500"), "taxable");
        scenario.addAccount(taxableAcct);
        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("200000.0000"), BigDecimal.ZERO, bd("0.0500"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        // First year: total=600k grows to 630k, withdrawal=4% of 600k = 24000
        // Taxable first: all 24000 from taxable pool
        var year1 = result.yearlyData().getFirst();
        assertThat(year1.withdrawals()).isGreaterThan(BigDecimal.ZERO);
        // Taxable should decrease, traditional should not (only taxable drained first)
        assertThat(year1.taxableBalance()).isLessThan(bd("315000")); // 300k * 1.05 = 315k, minus withdrawal
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000")); // 200k * 1.05, untouched
    }

    @Test
    void run_withdrawalOrderTraditionalFirst_drawsTraditionalFirst() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "traditional_first"}
                """.formatted(LocalDate.now().getYear() - 66));

        var taxableAcct = new ProjectionAccountEntity(
                scenario, null, bd("300000.0000"), BigDecimal.ZERO, bd("0.0500"), "taxable");
        scenario.addAccount(taxableAcct);
        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("200000.0000"), BigDecimal.ZERO, bd("0.0500"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // Traditional drawn first, so traditional should decrease significantly
        assertThat(year1.traditionalBalance()).isLessThan(bd("210000"));
        // Taxable is NOT drawn for the withdrawal itself (but tax deduction may hit it)
        // Key check: traditional decreased more than taxable relative to their post-growth values
        BigDecimal tradReduction = bd("210000").subtract(year1.traditionalBalance());
        BigDecimal taxableReduction = bd("315000").subtract(year1.taxableBalance());
        assertThat(tradReduction).isGreaterThan(taxableReduction);
    }

    @Test
    void run_withdrawalOrderRothFirst_drawsRothFirst() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "roth_first"}
                """.formatted(LocalDate.now().getYear() - 66));

        var taxableAcct = new ProjectionAccountEntity(
                scenario, null, bd("300000.0000"), BigDecimal.ZERO, bd("0.0500"), "taxable");
        scenario.addAccount(taxableAcct);
        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("200000.0000"), BigDecimal.ZERO, bd("0.0500"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // Taxable and traditional untouched, roth drains first
        assertThat(year1.taxableBalance()).isEqualByComparingTo(bd("315000.0000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
        assertThat(year1.rothBalance()).isLessThan(bd("105000"));
    }

    @Test
    void run_withdrawalOrderProRata_withdrawsProportionally() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        // 300k taxable, 200k traditional, 100k roth = 600k total
        // All grow at 5% to 630k, withdrawal = 4% of 600k = 24000
        // Pro rata: 315/630=50% from taxable, 210/630=33.3% trad, 105/630=16.7% roth
        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66));

        var taxableAcct = new ProjectionAccountEntity(
                scenario, null, bd("300000.0000"), BigDecimal.ZERO, bd("0.0500"), "taxable");
        scenario.addAccount(taxableAcct);
        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("200000.0000"), BigDecimal.ZERO, bd("0.0500"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // All three pools should be less than their post-growth values
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        assertThat(year1.traditionalBalance()).isLessThan(bd("210000"));
        assertThat(year1.rothBalance()).isLessThan(bd("105000"));
    }

    @Test
    void run_withdrawalOrderProRata_taxOnTraditionalPortion() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single", "withdrawal_order": "pro_rata"}
                """.formatted(LocalDate.now().getYear() - 66));

        var taxableAcct = new ProjectionAccountEntity(
                scenario, null, bd("300000.0000"), BigDecimal.ZERO, bd("0.0500"), "taxable");
        scenario.addAccount(taxableAcct);
        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("200000.0000"), BigDecimal.ZERO, bd("0.0500"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // Should have tax liability (from traditional portion), but less than all-traditional
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void run_fillBracketStrategy_convertsToFillBracket() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        // target_bracket_rate=0.12 means fill up to ceiling of 12% bracket = $48,475
        // other_income=0, so full bracket space available for conversion
        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("500000.0000"), BigDecimal.ZERO, bd("0.0700"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0700"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // Conversion should be bracket ceiling = $48,475 (minus $0 other income)
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("48475"));
    }

    @Test
    void run_fillBracketStrategy_withOtherIncome_reducesConversion() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        // Fill to 12% bracket ceiling ($48,475), other_income=30000
        // Available space = 48475 - 30000 = 18475
        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 30000, "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("500000.0000"), BigDecimal.ZERO, bd("0.0700"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0700"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("18475"));
    }

    @Test
    void run_fillBracketStrategy_traditionalExhausted_convertsOnlyAvailable() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        // Small traditional balance < fill amount
        // Traditional = 10k, after 7% growth = 10700, bracket fill = 48475
        // Should convert only 10700
        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "roth_conversion_strategy": "fill_bracket", "target_bracket_rate": 0.12}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("10000.0000"), BigDecimal.ZERO, bd("0.0700"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0700"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isNotNull();
        // Should be capped to available traditional balance (10700 after growth)
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("10700.0000"));
    }

    @Test
    void run_fixedAmountStrategy_backwardsCompatible() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        // No roth_conversion_strategy param, uses annual_roth_conversion as before
        var scenario = createScenario(
                LocalDate.now().plusYears(30),
                90,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": 25000}
                """.formatted(LocalDate.now().getYear() - 35));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("500000.0000"), BigDecimal.ZERO, bd("0.0700"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0700"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.rothConversionAmount()).isEqualByComparingTo(bd("25000"));
    }

    @Test
    void run_noWithdrawalOrderParam_defaultsTaxableFirst() {
        stubSingleBrackets();
        var engineTax = engineWithTax();

        var scenario = createScenario(
                LocalDate.now().minusYears(1),
                75,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 66));

        var taxableAcct = new ProjectionAccountEntity(
                scenario, null, bd("300000.0000"), BigDecimal.ZERO, bd("0.0500"), "taxable");
        scenario.addAccount(taxableAcct);
        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd("200000.0000"), BigDecimal.ZERO, bd("0.0500"), "traditional");
        scenario.addAccount(tradAcct);
        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd("100000.0000"), BigDecimal.ZERO, bd("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engineTax.run(scenario);

        var year1 = result.yearlyData().getFirst();
        // Default behavior = taxable first, traditional untouched
        assertThat(year1.taxableBalance()).isLessThan(bd("315000"));
        assertThat(year1.traditionalBalance()).isEqualByComparingTo(bd("210000.0000"));
    }

    private ProjectionScenarioEntity createScenario(LocalDate retirementDate, int endAge,
                                                     BigDecimal inflationRate, String paramsJson) {
        return new ProjectionScenarioEntity(tenant, "Test Scenario",
                retirementDate, endAge, inflationRate, paramsJson);
    }
}
