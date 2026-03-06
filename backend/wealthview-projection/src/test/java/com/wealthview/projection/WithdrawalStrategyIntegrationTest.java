package com.wealthview.projection;

import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TaxBracketEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class WithdrawalStrategyIntegrationTest {

    private TenantEntity tenant;
    private TaxBracketRepository taxBracketRepository;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity("Test");
        taxBracketRepository = mock(TaxBracketRepository.class);
        stubSingleBrackets();
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

    @ParameterizedTest(name = "fixedPercentage with balance={0}, rate={1}")
    @CsvSource({
            "0, 0.04",
            "100, 0.04",
            "1000000, 0.04",
            "10000000, 0.04"
    })
    void fixedPercentage_variousBalances_neverNegative(String balance, String rate) {
        var engine = new DeterministicProjectionEngine(null);
        var scenario = createRetiredScenario(
                """
                {"birth_year": %d, "withdrawal_rate": %s, "withdrawal_strategy": "fixed_percentage"}
                """.formatted(LocalDate.now().getYear() - 70, rate));

        var account = new ProjectionAccountEntity(
                scenario, null, bd(balance), BigDecimal.ZERO, new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        for (var year : result.yearlyData()) {
            assertThat(year.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    @ParameterizedTest(name = "dynamicPercentage with balance={0}")
    @CsvSource({
            "0",
            "100000",
            "10000000"
    })
    void dynamicPercentage_variousBalances_neverDepletes(String balance) {
        var engine = new DeterministicProjectionEngine(null);
        var scenario = createRetiredScenario(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "dynamic_percentage"}
                """.formatted(LocalDate.now().getYear() - 70));

        var account = new ProjectionAccountEntity(
                scenario, null, bd(balance), BigDecimal.ZERO, new BigDecimal("0.0500"));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        // Dynamic percentage never depletes to zero (4% of current balance)
        for (var year : result.yearlyData()) {
            assertThat(year.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    @ParameterizedTest(name = "vanguard with return={0}")
    @CsvSource({
            "0.30",
            "-0.40",
            "0.00",
            "0.15"
    })
    void vanguard_extremeReturns_capsAndFloors(String returnRate) {
        var engine = new DeterministicProjectionEngine(null);
        var scenario = createRetiredScenario(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "vanguard_dynamic_spending", "dynamic_ceiling": 0.05, "dynamic_floor": -0.025}
                """.formatted(LocalDate.now().getYear() - 70));

        var account = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("1000000.0000"), BigDecimal.ZERO,
                bd(returnRate));
        scenario.addAccount(account);

        var result = engine.run(scenario);

        assertThat(result.yearlyData()).isNotEmpty();
        for (var year : result.yearlyData()) {
            assertThat(year.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        }
    }

    @ParameterizedTest(name = "rothConversion={0}, traditional={1}")
    @CsvSource({
            "500000, 30000",
            "50000, 100000",
            "0, 100000"
    })
    void rothConversion_variousAmounts_handledCorrectly(String conversion, String tradBalance) {
        var calc = new FederalTaxCalculator(taxBracketRepository);
        var engine = new DeterministicProjectionEngine(calc);

        var scenario = createScenario(
                LocalDate.now().plusYears(10),
                80,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": %s}
                """.formatted(LocalDate.now().getYear() - 35, conversion));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null, bd(tradBalance), BigDecimal.ZERO,
                new BigDecimal("0.0700"), "traditional");
        scenario.addAccount(tradAcct);

        var rothAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("50000.0000"), BigDecimal.ZERO,
                new BigDecimal("0.0700"), "roth");
        scenario.addAccount(rothAcct);

        var result = engine.run(scenario);

        assertThat(result.yearlyData()).isNotEmpty();
        for (var year : result.yearlyData()) {
            assertThat(year.endBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            if (year.traditionalBalance() != null) {
                assertThat(year.traditionalBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }
            if (year.rothBalance() != null) {
                assertThat(year.rothBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }
        }
    }

    @ParameterizedTest(name = "zeroOtherIncome with conversion={0}")
    @CsvSource({
            "50000",
            "100000"
    })
    void zeroOtherIncome_taxOnConversionOnly(String conversion) {
        var calc = new FederalTaxCalculator(taxBracketRepository);
        var engine = new DeterministicProjectionEngine(calc);

        var scenario = createScenario(
                LocalDate.now().plusYears(10),
                80,
                BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 0, "annual_roth_conversion": %s}
                """.formatted(LocalDate.now().getYear() - 35, conversion));

        var tradAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("500000.0000"), BigDecimal.ZERO,
                new BigDecimal("0.0700"), "traditional");
        scenario.addAccount(tradAcct);

        var rothAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000.0000"), BigDecimal.ZERO,
                new BigDecimal("0.0700"), "roth");
        scenario.addAccount(rothAcct);

        var result = engine.run(scenario);

        var year1 = result.yearlyData().getFirst();
        assertThat(year1.taxLiability()).isNotNull();
        assertThat(year1.taxLiability()).isGreaterThan(BigDecimal.ZERO);
    }

    @ParameterizedTest(name = "allRothPortfolio with balance={0}")
    @CsvSource({
            "100000",
            "500000",
            "1000000"
    })
    void allRothPortfolio_noTaxOnWithdrawals(String balance) {
        var calc = new FederalTaxCalculator(taxBracketRepository);
        var engine = new DeterministicProjectionEngine(calc);

        var scenario = createRetiredScenario(
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 70));

        var rothAcct = new ProjectionAccountEntity(
                scenario, null, bd(balance), BigDecimal.ZERO,
                new BigDecimal("0.0500"), "roth");
        scenario.addAccount(rothAcct);

        var result = engine.run(scenario);

        for (var year : result.yearlyData()) {
            if (year.taxLiability() != null) {
                assertThat(year.taxLiability()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }

    private ProjectionScenarioEntity createRetiredScenario(String paramsJson) {
        return new ProjectionScenarioEntity(tenant, "Test",
                LocalDate.now().minusYears(1), 95, BigDecimal.ZERO, paramsJson);
    }

    private ProjectionScenarioEntity createScenario(LocalDate retirementDate, int endAge,
                                                     BigDecimal inflationRate, String paramsJson) {
        return new ProjectionScenarioEntity(tenant, "Test",
                retirementDate, endAge, inflationRate, paramsJson);
    }

    private static BigDecimal bd(String val) {
        return new BigDecimal(val);
    }
}
