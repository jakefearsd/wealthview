package com.wealthview.projection;

import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createRetiredInput;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.engineWithTax;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class WithdrawalStrategyIntegrationTest {

    private TaxBracketRepository taxBracketRepository;
    private StandardDeductionRepository standardDeductionRepository;

    @BeforeEach
    void setUp() {
        taxBracketRepository = mock(TaxBracketRepository.class);
        standardDeductionRepository = mock(StandardDeductionRepository.class);
        stubSingle2025(taxBracketRepository, standardDeductionRepository);
    }

    @ParameterizedTest(name = "fixedPercentage with balance={0}, rate={1}")
    @CsvSource({
            "0, 0.04",
            "100, 0.04",
            "1000000, 0.04",
            "10000000, 0.04"
    })
    void fixedPercentage_variousBalances_neverNegative(String balance, String rate) {
        var engine = new DeterministicProjectionEngine(null, null);
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": %s, "withdrawal_strategy": "fixed_percentage"}
                """.formatted(LocalDate.now().getYear() - 70, rate),
                List.of(acct(balance, "0", "0.0500")));

        var result = engine.run(input);

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
        var engine = new DeterministicProjectionEngine(null, null);
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "dynamic_percentage"}
                """.formatted(LocalDate.now().getYear() - 70),
                List.of(acct(balance, "0", "0.0500")));

        var result = engine.run(input);

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
        var engine = new DeterministicProjectionEngine(null, null);
        var input = createRetiredInput(
                """
                {"birth_year": %d, "withdrawal_rate": 0.04, "withdrawal_strategy": "vanguard_dynamic_spending", "dynamic_ceiling": 0.05, "dynamic_floor": -0.025}
                """.formatted(LocalDate.now().getYear() - 70),
                List.of(acct("1000000.0000", "0", returnRate)));

        var result = engine.run(input);

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
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(10), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "annual_roth_conversion": %s}
                """.formatted(LocalDate.now().getYear() - 35, conversion),
                List.of(
                        acct(tradBalance, "0", "0.0700", "traditional"),
                        acct("50000.0000", "0", "0.0700", "roth")));

        var result = engine.run(input);

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
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createInput(
                LocalDate.now().plusYears(10), 80, BigDecimal.ZERO,
                """
                {"birth_year": %d, "filing_status": "single", "other_income": 0, "annual_roth_conversion": %s}
                """.formatted(LocalDate.now().getYear() - 35, conversion),
                List.of(
                        acct("500000.0000", "0", "0.0700", "traditional"),
                        acct("100000.0000", "0", "0.0700", "roth")));

        var result = engine.run(input);

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
        var engine = engineWithTax(taxBracketRepository, standardDeductionRepository);

        var input = createRetiredInput(
                """
                {"birth_year": %d, "filing_status": "single"}
                """.formatted(LocalDate.now().getYear() - 70),
                List.of(acct(balance, "0", "0.0500", "roth")));

        var result = engine.run(input);

        for (var year : result.yearlyData()) {
            if (year.taxLiability() != null) {
                assertThat(year.taxLiability()).isEqualByComparingTo(BigDecimal.ZERO);
            }
        }
    }
}
