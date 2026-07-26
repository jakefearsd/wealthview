package com.wealthview.projection.testutil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.mortality.MortalityTable;

/**
 * Task 18: test-only fluent wrapper over {@link GuardrailOptimizationInput.Builder} (the
 * production builder Task 11 added), seeded with the de-facto canonical fixture shared by the
 * guardrail/Monte Carlo test suite: retirement 2030-01-01, birth year 1968, end age 90, 3%
 * inflation, a single $500k all-US taxable account, essential floor $30k, 10% nominal return, 200
 * trials, 95% confidence, seed 42L. Derived from the dominant fixture shape across {@code
 * OptimizationContextBuilderTest}'s {@code inputWith}/{@code mortalityToggleInput}/{@code
 * inputWithBirthYear} helpers (the tightest cluster of near-identical literal fixtures pre-migration)
 * and cross-checked against the majority of {@code MonteCarloSpendingOptimizerTest}'s deleted
 * {@code buildInputWithCashBuffer} defaults.
 *
 * <p>This class carries zero construction logic of its own -- every {@code with*} is a one-line
 * delegation to the production {@link GuardrailOptimizationInput.Builder} setter of the same name,
 * so it can never drift from the record's actual 42-component shape. Only components that at least
 * one migrated call site varies get a {@code with*} here; five components were UNIVERSAL constants
 * across every raw constructor call this builder replaced and are pinned via the production
 * builder's own defaults instead of exposed: {@code traditionalExhaustionBuffer=5} (set explicitly
 * below -- the production builder's own default is {@code 0}), {@code rmdBracketHeadroom=null},
 * {@code includeDepressionYears=false}, {@code communityProperty=false}, and {@code
 * longevityConditionalAge=null} (these four already match the production builder's un-set default,
 * so nothing extra is set for them). Add a {@code with*} here if a future test needs to vary one.
 */
public final class GuardrailOptimizationInputBuilder {

    private final GuardrailOptimizationInput.Builder delegate = GuardrailOptimizationInput.builder();

    private GuardrailOptimizationInputBuilder() {
        delegate.retirementDate(LocalDate.of(2030, 1, 1))
                .birthYear(1968)
                .endAge(90)
                .inflationRate(new BigDecimal("0.03"))
                .accounts(List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO, new BigDecimal("0.07"), "taxable")))
                .incomeSources(List.of())
                .essentialFloor(new BigDecimal("30000"))
                .terminalBalanceTarget(BigDecimal.ZERO)
                .returnMean(new BigDecimal("0.10"))
                .trialCount(200)
                .confidenceLevel(new BigDecimal("0.95"))
                .phases(List.of())
                .seed(42L)
                .portfolioFloor(BigDecimal.ZERO)
                .cashReturnRate(BigDecimal.ZERO)
                .traditionalExhaustionBuffer(5);
    }

    public static GuardrailOptimizationInputBuilder builder() {
        return new GuardrailOptimizationInputBuilder();
    }

    public GuardrailOptimizationInputBuilder withRetirementDate(LocalDate retirementDate) {
        delegate.retirementDate(retirementDate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withBirthYear(int birthYear) {
        delegate.birthYear(birthYear);
        return this;
    }

    public GuardrailOptimizationInputBuilder withEndAge(int endAge) {
        delegate.endAge(endAge);
        return this;
    }

    public GuardrailOptimizationInputBuilder withInflationRate(BigDecimal inflationRate) {
        delegate.inflationRate(inflationRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withAccounts(List<ProjectionAccountInput> accounts) {
        delegate.accounts(accounts);
        return this;
    }

    public GuardrailOptimizationInputBuilder withIncomeSources(List<ProjectionIncomeSourceInput> incomeSources) {
        delegate.incomeSources(incomeSources);
        return this;
    }

    public GuardrailOptimizationInputBuilder withEssentialFloor(BigDecimal essentialFloor) {
        delegate.essentialFloor(essentialFloor);
        return this;
    }

    public GuardrailOptimizationInputBuilder withTerminalBalanceTarget(BigDecimal terminalBalanceTarget) {
        delegate.terminalBalanceTarget(terminalBalanceTarget);
        return this;
    }

    public GuardrailOptimizationInputBuilder withReturnMean(@Nullable BigDecimal returnMean) {
        delegate.returnMean(returnMean);
        return this;
    }

    public GuardrailOptimizationInputBuilder withTrialCount(int trialCount) {
        delegate.trialCount(trialCount);
        return this;
    }

    public GuardrailOptimizationInputBuilder withConfidenceLevel(BigDecimal confidenceLevel) {
        delegate.confidenceLevel(confidenceLevel);
        return this;
    }

    public GuardrailOptimizationInputBuilder withPhases(List<GuardrailPhaseInput> phases) {
        delegate.phases(phases);
        return this;
    }

    public GuardrailOptimizationInputBuilder withSeed(Long seed) {
        delegate.seed(seed);
        return this;
    }

    public GuardrailOptimizationInputBuilder withPortfolioFloor(BigDecimal portfolioFloor) {
        delegate.portfolioFloor(portfolioFloor);
        return this;
    }

    public GuardrailOptimizationInputBuilder withMaxAnnualAdjustmentRate(
            @Nullable BigDecimal maxAnnualAdjustmentRate) {
        delegate.maxAnnualAdjustmentRate(maxAnnualAdjustmentRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withPhaseBlendYears(int phaseBlendYears) {
        delegate.phaseBlendYears(phaseBlendYears);
        return this;
    }

    public GuardrailOptimizationInputBuilder withCashReserveYears(int cashReserveYears) {
        delegate.cashReserveYears(cashReserveYears);
        return this;
    }

    public GuardrailOptimizationInputBuilder withCashReturnRate(BigDecimal cashReturnRate) {
        delegate.cashReturnRate(cashReturnRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withFilingStatus(@Nullable String filingStatus) {
        delegate.filingStatus(filingStatus);
        return this;
    }

    public GuardrailOptimizationInputBuilder withWithdrawalOrder(@Nullable String withdrawalOrder) {
        delegate.withdrawalOrder(withdrawalOrder);
        return this;
    }

    public GuardrailOptimizationInputBuilder withOptimizeConversions(boolean optimizeConversions) {
        delegate.optimizeConversions(optimizeConversions);
        return this;
    }

    public GuardrailOptimizationInputBuilder withConversionBracketRate(@Nullable BigDecimal conversionBracketRate) {
        delegate.conversionBracketRate(conversionBracketRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withRmdTargetBracketRate(@Nullable BigDecimal rmdTargetBracketRate) {
        delegate.rmdTargetBracketRate(rmdTargetBracketRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withDynamicSequencingBracketRate(
            @Nullable BigDecimal dynamicSequencingBracketRate) {
        delegate.dynamicSequencingBracketRate(dynamicSequencingBracketRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withDividendYield(@Nullable BigDecimal dividendYield) {
        delegate.dividendYield(dividendYield);
        return this;
    }

    public GuardrailOptimizationInputBuilder withFeeRate(@Nullable BigDecimal feeRate) {
        delegate.feeRate(feeRate);
        return this;
    }

    public GuardrailOptimizationInputBuilder withBaseYear(int baseYear) {
        delegate.baseYear(baseYear);
        return this;
    }

    public GuardrailOptimizationInputBuilder withInterestYield(@Nullable BigDecimal interestYield) {
        delegate.interestYield(interestYield);
        return this;
    }

    public GuardrailOptimizationInputBuilder withGateOnAdaptiveRules(boolean gateOnAdaptiveRules) {
        delegate.gateOnAdaptiveRules(gateOnAdaptiveRules);
        return this;
    }

    public GuardrailOptimizationInputBuilder withSpouseBirthYear(@Nullable Integer spouseBirthYear) {
        delegate.spouseBirthYear(spouseBirthYear);
        return this;
    }

    public GuardrailOptimizationInputBuilder withPrimaryDeathAge(@Nullable Integer primaryDeathAge) {
        delegate.primaryDeathAge(primaryDeathAge);
        return this;
    }

    public GuardrailOptimizationInputBuilder withSpouseDeathAge(@Nullable Integer spouseDeathAge) {
        delegate.spouseDeathAge(spouseDeathAge);
        return this;
    }

    public GuardrailOptimizationInputBuilder withSurvivorSpendingFactor(
            @Nullable BigDecimal survivorSpendingFactor) {
        delegate.survivorSpendingFactor(survivorSpendingFactor);
        return this;
    }

    public GuardrailOptimizationInputBuilder withStochasticMortality(@Nullable Boolean stochasticMortality) {
        delegate.stochasticMortality(stochasticMortality);
        return this;
    }

    public GuardrailOptimizationInputBuilder withPrimarySex(@Nullable String primarySex) {
        delegate.primarySex(primarySex);
        return this;
    }

    public GuardrailOptimizationInputBuilder withSpouseSex(@Nullable String spouseSex) {
        delegate.spouseSex(spouseSex);
        return this;
    }

    public GuardrailOptimizationInputBuilder withMortalityTable(@Nullable MortalityTable mortalityTable) {
        delegate.mortalityTable(mortalityTable);
        return this;
    }

    public GuardrailOptimizationInput build() {
        return delegate.build();
    }
}
