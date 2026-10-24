package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wealthview.core.projection.BondMaturityReturnsProvider.MaturityReturnMatrix;
import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.BondMaturity;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FixedSpendingTentSimulatorTest {

    private static final long SEED = 20261024L;

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static RealReturnMatrix zeroMarkets() {
        return new RealReturnMatrix(new int[]{1, 2, 3, 4}, AssetClass.values(),
                new double[4][AssetClass.values().length]);
    }

    private static MaturityReturnMatrix zeroMaturities() {
        return new MaturityReturnMatrix(new int[]{1, 2, 3, 4}, BondMaturity.values(),
                new double[4][BondMaturity.values().length]);
    }

    private static BondMaturityValues cashShares() {
        return new BondMaturityValues(bd("1"), bd("0"), bd("0"), bd("0"));
    }

    private static BondTentConfig cashTent() {
        var yields = new BondMaturityValues(bd("0.025"), bd("0"), bd("0"), bd("0"));
        return new BondTentConfig(bd("2"), 0, List.of(new BondTentMix("Cash", cashShares())), yields);
    }

    private static List<ProjectionAccountInput> taxable(String balance) {
        return List.of(new HypotheticalAccountInput(bd(balance), BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), "taxable"));
    }

    private static GuardrailOptimizationInput flatInput(int endAge, BondTentConfig tent) {
        return GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(LocalDate.of(2030, 1, 1))
                .withBirthYear(1965)
                .withBaseYear(2030)
                .withEndAge(endAge)
                .withInflationRate(bd("0.025"))
                .withAccounts(taxable("1000000"))
                .withEssentialFloor(bd("1"))
                .withFeeRate(bd("0"))
                .withDividendYield(bd("0"))
                .withInterestYield(bd("0"))
                .withTrialCount(50)
                .withSeed(SEED)
                .withBondTent(tent)
                .build();
    }

    private static ProjectionInput projectionOf(GuardrailOptimizationInput input, String essential,
                                                String discretionary) {
        return new ProjectionInput(UUID.nameUUIDFromBytes("sim".getBytes()), "Sim", input.retirementDate(),
                input.endAge(), input.inflationRate(), "{}", input.accounts(),
                new SpendingProfileInput(bd(essential), bd(discretionary), "[]"), 2030, List.of(), null,
                List.of());
    }

    private static FixedSpendingTentSimulator flatSimulator() {
        return new FixedSpendingTentSimulator(null, zeroMarkets(), zeroMaturities());
    }

    private static FixedSpendingTentSimulator testMarketSimulator() {
        return new FixedSpendingTentSimulator(null, ProjectionTestFixtures.TEST_CMA_MATRIX,
                ProjectionTestFixtures.TEST_MATURITY_MATRIX);
    }

    @Test
    void simulate_flatMarkets_tentIsHeldAndCoversItsPlannedYears() {
        var input = flatInput(90, cashTent());

        var result = flatSimulator().simulate(input, projectionOf(input, "30000", "0"));

        var noTent = result.columns().get(0);
        assertThat(noTent.name()).isEqualTo("No tent");
        assertThat(noTent.tentTarget()).isNull();
        assertThat(noTent.successRate()).isEqualByComparingTo("1.0000");
        assertThat(noTent.yearsCoveredMedian()).isNull();
        assertThat(noTent.yearsCoveredWorst10()).isNull();
        assertThat(noTent.balanceAt90Median()).isEqualByComparingTo("250000.00");
        var cash = result.columns().get(1);
        assertThat(cash.name()).isEqualTo("Cash");
        assertThat(cash.tentTarget()).isEqualByComparingTo("60000.00");
        assertThat(cash.successRate()).isEqualByComparingTo("1.0000");
        assertThat(cash.yearsCoveredMedian()).isEqualByComparingTo("2.00");
        assertThat(cash.yearsCoveredWorst10()).isEqualByComparingTo("2.00");
        assertThat(cash.balanceAt90Median()).isEqualByComparingTo("250000.00");
        assertThat(result.trialCount()).isEqualTo(50);
        assertThat(result.plannedYears()).isEqualByComparingTo("2.00");
    }

    @Test
    void simulate_guardrailPlanWithConversions_paysItsRecommendedSpendingAndKeepsTheTotal() {
        var tent = cashTent();
        var base = flatInput(90, tent);
        var pools = List.<ProjectionAccountInput>of(
                new HypotheticalAccountInput(bd("600000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.empty(), "traditional"),
                new HypotheticalAccountInput(bd("400000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.empty(), "roth"));
        var input = GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(base.retirementDate()).withBirthYear(1965).withBaseYear(2030).withEndAge(90)
                .withInflationRate(bd("0.025")).withAccounts(pools).withEssentialFloor(bd("1"))
                .withFeeRate(bd("0")).withDividendYield(bd("0")).withInterestYield(bd("0"))
                .withTrialCount(50).withSeed(SEED).withBondTent(tent).build();
        var yearly = new ArrayList<GuardrailYearlySpending>();
        var conversions = new HashMap<Integer, BigDecimal>();
        for (int year = 2030; year < 2060; year++) {
            yearly.add(new GuardrailYearlySpending(year, year - 1965, bd("20000"), bd("0"), bd("0"), bd("20000"),
                    bd("0"), bd("0"), bd("20000"), "Core"));
            if (year < 2040) {
                conversions.put(year, bd("50000"));
            }
        }
        var projection = new ProjectionInput(UUID.nameUUIDFromBytes("g".getBytes()), "Sim", input.retirementDate(),
                input.endAge(), input.inflationRate(), "{}", input.accounts(),
                new SpendingProfileInput(bd("0"), bd("0"), "[]"), 2030, List.of(),
                new GuardrailSpendingInput(yearly, conversions), List.of(), null);

        var result = flatSimulator().simulate(input, projection);

        // The tier profile pays nothing; only the guardrail schedule's 20,000 a year can take 25 years off the
        // 1,000,000, and conversions only move money between the pools on flat markets with no tax calculator.
        assertThat(result.columns()).allSatisfy(c -> {
            assertThat(c.successRate()).isEqualByComparingTo("1.0000");
            assertThat(c.balanceAt90Median()).isEqualByComparingTo("500000.00");
        });
        assertThat(result.columns().get(1).tentTarget()).isEqualByComparingTo("40000.00");
    }

    @Test
    void simulate_spendingAboveThePortfolio_failsEveryTrial() {
        var input = flatInput(90, cashTent());

        var result = flatSimulator().simulate(input, projectionOf(input, "0", "50000"));

        assertThat(result.columns()).allSatisfy(c -> assertThat(c.successRate()).isEqualByComparingTo("0.0000"));
    }

    @Test
    void simulate_twoIdenticalMixes_reportIdenticalColumns() {
        var shares = new BondMaturityValues(bd("0.2"), bd("0.3"), bd("0.5"), bd("0"));
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        var tent = new BondTentConfig(bd("5"), 3,
                List.of(new BondTentMix("A", shares), new BondTentMix("B", shares)), yields);
        var input = pinnedInput(tent);

        var result = testMarketSimulator().simulate(input, projectionOf(input, "40000", "0"));

        assertThat(result.columns().get(1)).usingRecursiveComparison().ignoringFields("name")
                .isEqualTo(result.columns().get(2));
    }

    @Test
    void simulate_columns_noTentThenEachMixInOrder() {
        var yields = new BondMaturityValues(bd("0.025"), bd("0"), bd("0"), bd("0"));
        var tent = new BondTentConfig(bd("2"), 0, List.of(new BondTentMix("Cash", cashShares()),
                new BondTentMix("Other", new BondMaturityValues(bd("0.5"), bd("0.5"), bd("0"), bd("0")))), yields);
        var input = flatInput(90, tent);

        var result = flatSimulator().simulate(input, projectionOf(input, "30000", "0"));

        assertThat(result.columns()).extracting("name").containsExactly("No tent", "Cash", "Other");
    }

    @Test
    void simulate_sameSeed_isDeterministic() {
        var input = pinnedInput(balancedTent());
        var projection = projectionOf(input, "40000", "0");

        var first = testMarketSimulator().simulate(input, projection);
        var second = testMarketSimulator().simulate(input, projection);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void simulate_horizonEndsBefore90_hasNoBalanceAt90() {
        var input = flatInput(85, cashTent());

        var result = flatSimulator().simulate(input, projectionOf(input, "30000", "0"));

        assertThat(result.columns()).allSatisfy(c -> assertThat(c.balanceAt90Median()).isNull());
    }

    @Test
    void simulate_noTent_throws() {
        var input = flatInput(90, null);

        assertThatThrownBy(() -> flatSimulator().simulate(input, projectionOf(input, "30000", "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent analysis needs a spending plan and a bond tent");
    }

    @Test
    void simulate_noSpendingPlan_throws() {
        var input = flatInput(90, cashTent());
        var noPlan = new ProjectionInput(UUID.nameUUIDFromBytes("sim".getBytes()), "Sim", input.retirementDate(),
                input.endAge(), input.inflationRate(), "{}", input.accounts(), null, 2030, List.of(), null,
                List.of());

        assertThatThrownBy(() -> flatSimulator().simulate(input, noPlan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A bond tent analysis needs a spending plan and a bond tent");
    }

    private static BondTentConfig balancedTent() {
        var shares = new BondMaturityValues(bd("0.2"), bd("0.3"), bd("0.5"), bd("0"));
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        return new BondTentConfig(bd("5"), 3, List.of(new BondTentMix("Balanced", shares)), yields);
    }

    /** MonteCarloBondTentCharacterizationTest's fixture: 2027 base, 2030 retirement, 600k taxable + 400k traditional. */
    private static GuardrailOptimizationInput pinnedInput(BondTentConfig tent) {
        List<ProjectionAccountInput> accounts = List.of(
                new HypotheticalAccountInput(bd("600000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.empty(), "taxable"),
                new HypotheticalAccountInput(bd("400000"), BigDecimal.ZERO, AssetAllocation.ALL_US,
                        Optional.empty(), "traditional"));
        return GuardrailOptimizationInputBuilder.builder()
                .withRetirementDate(LocalDate.of(2030, 1, 1))
                .withBaseYear(2027)
                .withAccounts(accounts)
                .withEssentialFloor(bd("40000"))
                .withTrialCount(1000)
                .withConfidenceLevel(bd("0.90"))
                .withSeed(SEED)
                .withBondTent(tent)
                .build();
    }

    @Test
    void simulate_bondTentFixture_matchesPinnedValues() {
        var input = pinnedInput(balancedTent());

        var result = testMarketSimulator().simulate(input, projectionOf(input, "40000", "0"));

        // Recorded once on 2026-10-24 by running simulate on this fixture (tier spending 40,000, the optimizer
        // characterization fixture's accounts, seed and tent) and reading the columns. The target was checked by
        // hand: 40,000 x sum(1 / 1.015369^k, k = 1..5), the Balanced mix's real yield at 3% inflation, is
        // 191,099.44. Nothing fails at 40,000, so the next test pins a plan that stresses the portfolio.
        assertThat(result.columns()).hasSize(2);
        assertThat(result.columns().get(0).successRate()).isEqualByComparingTo("1.0000");
        assertThat(result.columns().get(0).balanceAt90Median()).isEqualByComparingTo("4097694.44");
        assertThat(result.columns().get(1).tentTarget()).isEqualByComparingTo("191099.44");
        assertThat(result.columns().get(1).successRate()).isEqualByComparingTo("1.0000");
        assertThat(result.columns().get(1).yearsCoveredMedian()).isEqualByComparingTo("4.96");
        assertThat(result.columns().get(1).balanceAt90Median()).isEqualByComparingTo("3963619.89");
    }

    @Test
    void simulate_bondTentFixtureWithAStressedPlan_matchesPinnedValues() {
        var input = pinnedInput(balancedTent());

        var result = testMarketSimulator().simulate(input, projectionOf(input, "70000", "0"));

        // Recorded once on 2026-10-24 the same way, at tier spending 70,000, near the optimizer's own recommended
        // first-year spending of 69,475 on this fixture, whose pinned tent target is 331,916.89. This tent target
        // scales that by 70,000 / 69,475.
        assertThat(result.columns().get(0).successRate()).isEqualByComparingTo("0.8800");
        assertThat(result.columns().get(0).balanceAt90Median()).isEqualByComparingTo("1816071.73");
        assertThat(result.columns().get(1).tentTarget()).isEqualByComparingTo("334424.01");
        assertThat(result.columns().get(1).successRate()).isEqualByComparingTo("0.8820");
        assertThat(result.columns().get(1).yearsCoveredMedian()).isEqualByComparingTo("4.96");
        assertThat(result.columns().get(1).yearsCoveredWorst10()).isEqualByComparingTo("1.67");
        assertThat(result.columns().get(1).balanceAt90Median()).isEqualByComparingTo("1592793.07");
    }
}
