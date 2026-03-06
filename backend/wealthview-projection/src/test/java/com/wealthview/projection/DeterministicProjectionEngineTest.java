package com.wealthview.projection;

import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeterministicProjectionEngineTest {

    private DeterministicProjectionEngine engine;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        engine = new DeterministicProjectionEngine();
        tenant = new TenantEntity("Test");
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

    private ProjectionScenarioEntity createScenario(LocalDate retirementDate, int endAge,
                                                     BigDecimal inflationRate, String paramsJson) {
        return new ProjectionScenarioEntity(tenant, "Test Scenario",
                retirementDate, endAge, inflationRate, paramsJson);
    }
}
