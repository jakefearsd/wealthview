package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.acct;
import static com.wealthview.projection.testutil.ProjectionTestFixtures.createInput;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-master characterization test for {@link DeterministicProjectionEngine}.
 *
 * <p>This test pins the engine's full public-API behavior with a single realistic,
 * fully-deterministic fixture: a three-pool portfolio (traditional / roth / taxable),
 * a fixed {@code referenceYear} so the projection is reproducible across calendar time,
 * and a withdrawal-rate spending strategy. Every captured value below was produced by
 * the engine itself and sanity-checked for plausibility (positive ending balance,
 * constant-real withdrawals, contributions only while working).
 *
 * <p>It exists ahead of the Phase 3 decomposition of this God-class — the assertions
 * here are the behavior contract the decomposition must not break.
 */
class DeterministicProjectionEngineCharacterizationTest {

    /**
     * Fixed-reference projection so absolute calendar years are stable. Retirement at
     * 2040 (age 55), end age 90, 3% inflation, 4% withdrawal rate.
     */
    private ProjectionInput goldenInput() {
        var base = createInput(
                LocalDate.of(2040, 1, 1), 90, bd("0.0300"),
                """
                {"birth_year": 1985, "withdrawal_rate": 0.04}
                """,
                List.of(
                        acct("250000.0000", "15000.0000", "0.0600", "traditional"),
                        acct("80000.0000", "6000.0000", "0.0700", "roth"),
                        acct("120000.0000", "9000.0000", "0.0500", "taxable")),
                null, List.of());
        // referenceYear pins the projection's notion of "current year" to 2025.
        return new ProjectionInput(
                base.scenarioId(), base.scenarioName(), base.retirementDate(),
                base.endAge(), base.inflationRate(), base.paramsJson(),
                base.accounts(), base.spendingProfile(), 2025,
                base.incomeSources(), base.guardrailSpending(), base.properties());
    }

    @Test
    void run_threePoolWithdrawalRateScenario_pinsTopLevelResult() {
        var engine = new DeterministicProjectionEngine(null, null);

        ProjectionResultResponse result = engine.run(goldenInput());

        assertThat(result.yearlyData()).hasSize(50);
        assertThat(result.yearsInRetirement()).isEqualTo(35);
        assertThat(result.finalBalance()).isEqualByComparingTo(bd("686717.8994"));
    }

    @Test
    void run_threePoolWithdrawalRateScenario_pinsFirstWorkingYear() {
        var engine = new DeterministicProjectionEngine(null, null);

        ProjectionYearDto year1 = engine.run(goldenInput()).yearlyData().getFirst();

        assertThat(year1.year()).isEqualTo(2025);
        assertThat(year1.age()).isEqualTo(40);
        assertThat(year1.retired()).isFalse();
        assertThat(year1.startBalance()).isEqualByComparingTo(bd("450000.0000"));
        assertThat(year1.contributions()).isEqualByComparingTo(bd("30000.0000"));
        // Real terms: per-pool nominal overrides deflated at the SCENARIO's own 3% inflation
        // ((1+X)/1.03-1), matching the rate income/SS/spending deflate at.
        assertThat(year1.growth()).isEqualByComparingTo(bd("13563.1063"));
        assertThat(year1.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year1.endBalance()).isEqualByComparingTo(bd("493563.1063")); // 450000 + 30000 + 13563.1063
    }

    @Test
    void run_threePoolWithdrawalRateScenario_pinsLastWorkingYear() {
        var engine = new DeterministicProjectionEngine(null, null);

        ProjectionYearDto year = engine.run(goldenInput()).yearlyData().get(14);

        assertThat(year.year()).isEqualTo(2039);
        assertThat(year.age()).isEqualTo(54);
        assertThat(year.retired()).isFalse();
        assertThat(year.contributions()).isEqualByComparingTo(bd("30000.0000"));
        assertThat(year.withdrawals()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year.endBalance()).isEqualByComparingTo(bd("1253371.0714"));
    }

    @Test
    void run_threePoolWithdrawalRateScenario_pinsFirstRetirementYear() {
        var engine = new DeterministicProjectionEngine(null, null);

        ProjectionYearDto year = engine.run(goldenInput()).yearlyData().get(15);

        assertThat(year.year()).isEqualTo(2040);
        assertThat(year.age()).isEqualTo(55);
        assertThat(year.retired()).isTrue();
        assertThat(year.contributions()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(year.startBalance()).isEqualByComparingTo(bd("1253371.0714"));
        assertThat(year.growth()).isEqualByComparingTo(bd("35994.1323"));
        assertThat(year.withdrawals()).isEqualByComparingTo(bd("50134.8429"));
        assertThat(year.endBalance()).isEqualByComparingTo(bd("1239230.3608"));
    }

    @Test
    void run_threePoolWithdrawalRateScenario_pinsSecondRetirementYearInflationGrowth() {
        var engine = new DeterministicProjectionEngine(null, null);

        ProjectionYearDto year = engine.run(goldenInput()).yearlyData().get(16);

        assertThat(year.year()).isEqualTo(2041);
        assertThat(year.age()).isEqualTo(56);
        assertThat(year.retired()).isTrue();
        // Real terms: withdrawal held constant real year-over-year (no nominal escalation).
        assertThat(year.withdrawals()).isEqualByComparingTo(bd("50134.8429"));
        assertThat(year.endBalance()).isEqualByComparingTo(bd("1225204.6265"));
    }

    @Test
    void run_threePoolWithdrawalRateScenario_pinsFinalYear() {
        var engine = new DeterministicProjectionEngine(null, null);

        var data = engine.run(goldenInput()).yearlyData();
        ProjectionYearDto last = data.getLast();

        assertThat(last.year()).isEqualTo(2074);
        assertThat(last.age()).isEqualTo(89);
        assertThat(last.retired()).isTrue();
        assertThat(last.startBalance()).isEqualByComparingTo(bd("709306.8464"));
        assertThat(last.growth()).isEqualByComparingTo(bd("27545.8959"));
        assertThat(last.withdrawals()).isEqualByComparingTo(bd("50134.8429"));
        assertThat(last.endBalance()).isEqualByComparingTo(bd("686717.8994"));
    }

    @Test
    void run_threePoolWithdrawalRateScenario_endBalanceChainIsConsistent() {
        // Structural invariant: each year's startBalance equals the prior endBalance,
        // and end = start + contributions + growth - withdrawals.
        var engine = new DeterministicProjectionEngine(null, null);

        var data = engine.run(goldenInput()).yearlyData();

        for (int i = 0; i < data.size(); i++) {
            ProjectionYearDto y = data.get(i);
            BigDecimal expectedEnd = y.startBalance()
                    .add(y.contributions())
                    .add(y.growth())
                    .subtract(y.withdrawals());
            assertThat(y.endBalance()).isEqualByComparingTo(expectedEnd);
            if (i > 0) {
                assertThat(y.startBalance())
                        .isEqualByComparingTo(data.get(i - 1).endBalance());
            }
        }
    }
}
