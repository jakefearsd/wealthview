package com.wealthview.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.BondMaturityValues;
import com.wealthview.core.projection.dto.BondTentConfig;
import com.wealthview.core.projection.dto.BondTentMix;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.testutil.GuardrailOptimizationInputBuilder;
import com.wealthview.projection.testutil.ProjectionTestFixtures;
import com.wealthview.projection.testutil.SsaMortalityTables;

import static com.wealthview.core.testutil.TaxBracketFixtures.stubMfj2025;
import static com.wealthview.core.testutil.TaxBracketFixtures.stubSingle2025;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the bond tent through the two passes the plain optimizer tests do not reach: the joint Roth-conversion
 * search (its own generated search paths) and the stochastic-mortality evaluation (its own trial config).
 * A pass that dropped the tent would either throw (a plan without recorded paths) or ignore it, and in both
 * cases these assertions fail.
 */
class MonteCarloTentConversionAndMortalityTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static BondTentConfig balancedTent() {
        var shares = new BondMaturityValues(bd("0.2"), bd("0.3"), bd("0.5"), bd("0"));
        var yields = new BondMaturityValues(bd("0.0394"), bd("0.0465"), bd("0.048"), bd("0.0539"));
        return new BondTentConfig(bd("5"), 0, List.of(new BondTentMix("Balanced", shares)), yields);
    }

    private static FederalTaxCalculator realCalc() {
        var brackets = Mockito.mock(TaxBracketRepository.class);
        var deductions = Mockito.mock(StandardDeductionRepository.class);
        stubSingle2025(brackets, deductions);
        stubMfj2025(brackets, deductions);
        return new FederalTaxCalculator(brackets, deductions);
    }

    private static MonteCarloSpendingOptimizer optimizer() {
        return new MonteCarloSpendingOptimizer(realCalc(), ProjectionTestFixtures.TEST_CMA_MATRIX,
                ProjectionTestFixtures.TEST_MATURITY_MATRIX);
    }

    // === joint conversion search ===

    /** Pool-bearing, conversions-enabled, non-dynamic-sequencing order, so jointSearch actually runs. */
    private static GuardrailOptimizationInput conversionsInput(BondTentConfig tent) {
        return GuardrailOptimizationInputBuilder.builder()
                .withEndAge(92)
                .withAccounts(List.of(
                        new HypotheticalAccountInput(bd("400000"), BigDecimal.ZERO, null, "taxable"),
                        new HypotheticalAccountInput(bd("900000"), BigDecimal.ZERO, null, "traditional"),
                        new HypotheticalAccountInput(bd("200000"), BigDecimal.ZERO, null, "roth")))
                .withEssentialFloor(bd("50000"))
                .withReturnMean(bd("0.06"))
                .withTrialCount(300)
                .withConfidenceLevel(bd("0.90"))
                .withPhases(List.of(new GuardrailPhaseInput("All", 62, null, 1)))
                .withSeed(20261024L)
                .withFilingStatus("single")
                .withWithdrawalOrder("taxable_first")
                .withOptimizeConversions(true)
                .withConversionBracketRate(bd("0.22"))
                .withRmdTargetBracketRate(bd("0.12"))
                .withBondTent(tent)
                .build();
    }

    @Test
    void optimize_tentWithConversionSearch_runsTheJointSearchWithTheTent() {
        var withTent = optimizer().optimize(conversionsInput(balancedTent()));
        var withoutTent = optimizer().optimize(conversionsInput(null));

        assertThat(withTent.conversionSchedule()).isNotNull();
        assertThat(withTent.bondTentTarget()).isNotNull();
        assertThat(withTent.medianFinalBalance()).isNotEqualByComparingTo(withoutTent.medianFinalBalance());
    }

    // === stochastic mortality ===

    private static GuardrailOptimizationInput householdInput(BondTentConfig tent) {
        List<ProjectionAccountInput> accounts = List.of(
                account("800000", "500000", "taxable", "joint"),
                account("900000", "900000", "traditional", "primary"),
                account("400000", "400000", "traditional", "spouse"));
        List<ProjectionIncomeSourceInput> income = List.of(
                income("Primary Social Security", IncomeSourceType.SOCIAL_SECURITY, "38000", "primary", "1"),
                income("Spouse Social Security", IncomeSourceType.SOCIAL_SECURITY, "22000", "spouse", "1"));
        return GuardrailOptimizationInput.builder()
                .retirementDate(LocalDate.of(2030, 1, 1)).birthYear(1958).endAge(108)
                .inflationRate(bd("0.025")).accounts(accounts).incomeSources(income)
                .essentialFloor(bd("180000")).terminalBalanceTarget(BigDecimal.ZERO)
                .trialCount(500).confidenceLevel(bd("0.90"))
                .phases(List.of(new GuardrailPhaseInput("Retirement", 72, null, 1)))
                .seed(20261024L).portfolioFloor(BigDecimal.ZERO).phaseBlendYears(0)
                .filingStatus("married_filing_jointly").withdrawalOrder("taxable_first")
                .traditionalExhaustionBuffer(5).baseYear(2025).gateOnAdaptiveRules(true)
                .spouseBirthYear(1966).primaryDeathAge(85).spouseDeathAge(90)
                .survivorSpendingFactor(bd("0.75"))
                .stochasticMortality(Boolean.TRUE).primarySex("male").spouseSex("female")
                .longevityConditionalAge(95).mortalityTable(SsaMortalityTables.load())
                .bondTent(tent).build();
    }

    private static OptimizationSetup contextFor(GuardrailOptimizationInput input) {
        return new OptimizationContextBuilder(realCalc()).build(input, input.accounts(),
                ProjectionTestFixtures.TEST_CMA_MATRIX, ProjectionTestFixtures.TEST_MATURITY_MATRIX);
    }

    @Test
    void evaluate_sameScheduleWithAndWithoutTent_givesDifferentTrialOutcomes() {
        var withTentCtx = contextFor(householdInput(balancedTent()));
        var withoutTentCtx = contextFor(householdInput(null));
        double[] discretionary = new double[withTentCtx.sim().years()];
        java.util.Arrays.fill(discretionary, 60_000);
        var evaluator = new StochasticMortalityEvaluator(new TrialSimulator());

        var withTent = evaluator.evaluate(withTentCtx, discretionary, null, null);
        var withoutTent = evaluator.evaluate(withoutTentCtx, discretionary, null, null);

        assertThat(withTentCtx.sim().tentPlan()).isNotNull();
        assertThat(withTent.success()).isNotEqualTo(withoutTent.success());
    }

    private static HypotheticalAccountInput account(String balance, String basis, String type, String owner) {
        return new HypotheticalAccountInput(bd(balance), BigDecimal.ZERO, AssetAllocation.ALL_US,
                Optional.empty(), bd(basis), type, owner);
    }

    private static ProjectionIncomeSourceInput income(String name, IncomeSourceType type, String amount,
                                                      String owner, String survivorPercent) {
        return new ProjectionIncomeSourceInput(
                UUID.nameUUIDFromBytes((name + owner).getBytes()), name, type, bd(amount),
                62, null, bd("0.025"), false, "taxable",
                null, null, null, null, null, null, owner, bd(survivorPercent));
    }
}
