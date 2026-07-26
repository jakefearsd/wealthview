package com.wealthview.core.testutil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.lang.Nullable;

import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceInput;
import com.wealthview.core.projection.dto.ScenarioRequest;

/**
 * Task 19: test-only fluent builder for {@link ScenarioRequest} (a plain 37-component record with
 * no production builder of its own), seeded with the de-facto canonical fixture shared by
 * {@code ScenarioCrudServiceTest} and {@code ScenarioParamsTest}: name "Plan", retirement date
 * 2055-01-01, end age 90, 3% inflation, an empty accounts list. Derived from the dominant literal
 * fixture across both files' raw {@code new ScenarioRequest(...)} call sites and null-padding
 * helper methods (the tightest cluster of near-identical constructions pre-migration).
 *
 * <p>Only components that at least one migrated call site varies get a {@code with*} here; every
 * other component (e.g. {@code otherIncome}, {@code annualRothConversion},
 * {@code dynamicSequencingBracketRate}, {@code rothConversionStartYear}, {@code state},
 * {@code primaryResidencePropertyTax}, {@code primaryResidenceMortgageInterest},
 * {@code spendingProfileId}, {@code useGuardrailProfile}) was UNIVERSALLY {@code null} across every
 * raw constructor call this builder replaced, which already matches the record's own natural
 * default -- nothing extra is set for them. Add a {@code with*} here if a future test needs to vary
 * one. {@code incomeSources} defaults to {@code null} (not an empty list) -- the dominant literal
 * across both files, and {@code ScenarioCrudService.saveIncomeSourceLinks} is null-safe.
 */
public final class ScenarioRequestBuilder {

    private String name = "Plan";
    private LocalDate retirementDate = LocalDate.of(2055, 1, 1);
    private Integer endAge = 90;
    private BigDecimal inflationRate = new BigDecimal("0.03");
    private Integer birthYear;
    private BigDecimal withdrawalRate;
    private String withdrawalStrategy;
    private BigDecimal dynamicCeiling;
    private BigDecimal dynamicFloor;
    private String filingStatus;
    private String withdrawalOrder;
    private String rothConversionStrategy;
    private BigDecimal targetBracketRate;
    private BigDecimal dividendYield;
    private BigDecimal feeRate;
    private Boolean includeDepressionYears;
    private BigDecimal interestYield;
    private Integer spouseBirthYear;
    private Integer primaryDeathAge;
    private Integer spouseDeathAge;
    private BigDecimal survivorSpendingFactor;
    private Boolean communityProperty;
    private Boolean stochasticMortality;
    private String primarySex;
    private String spouseSex;
    private Integer longevityConditionalAge;
    private List<CreateProjectionAccountRequest> accounts = List.of();
    private List<ScenarioIncomeSourceInput> incomeSources;

    private ScenarioRequestBuilder() {
    }

    public static ScenarioRequestBuilder builder() {
        return new ScenarioRequestBuilder();
    }

    public ScenarioRequestBuilder withName(String name) {
        this.name = name;
        return this;
    }

    public ScenarioRequestBuilder withRetirementDate(LocalDate retirementDate) {
        this.retirementDate = retirementDate;
        return this;
    }

    public ScenarioRequestBuilder withEndAge(Integer endAge) {
        this.endAge = endAge;
        return this;
    }

    public ScenarioRequestBuilder withInflationRate(BigDecimal inflationRate) {
        this.inflationRate = inflationRate;
        return this;
    }

    public ScenarioRequestBuilder withBirthYear(@Nullable Integer birthYear) {
        this.birthYear = birthYear;
        return this;
    }

    public ScenarioRequestBuilder withWithdrawalRate(@Nullable BigDecimal withdrawalRate) {
        this.withdrawalRate = withdrawalRate;
        return this;
    }

    public ScenarioRequestBuilder withWithdrawalStrategy(@Nullable String withdrawalStrategy) {
        this.withdrawalStrategy = withdrawalStrategy;
        return this;
    }

    public ScenarioRequestBuilder withDynamicCeiling(@Nullable BigDecimal dynamicCeiling) {
        this.dynamicCeiling = dynamicCeiling;
        return this;
    }

    public ScenarioRequestBuilder withDynamicFloor(@Nullable BigDecimal dynamicFloor) {
        this.dynamicFloor = dynamicFloor;
        return this;
    }

    public ScenarioRequestBuilder withFilingStatus(@Nullable String filingStatus) {
        this.filingStatus = filingStatus;
        return this;
    }

    public ScenarioRequestBuilder withWithdrawalOrder(@Nullable String withdrawalOrder) {
        this.withdrawalOrder = withdrawalOrder;
        return this;
    }

    public ScenarioRequestBuilder withRothConversionStrategy(@Nullable String rothConversionStrategy) {
        this.rothConversionStrategy = rothConversionStrategy;
        return this;
    }

    public ScenarioRequestBuilder withTargetBracketRate(@Nullable BigDecimal targetBracketRate) {
        this.targetBracketRate = targetBracketRate;
        return this;
    }

    public ScenarioRequestBuilder withDividendYield(@Nullable BigDecimal dividendYield) {
        this.dividendYield = dividendYield;
        return this;
    }

    public ScenarioRequestBuilder withFeeRate(@Nullable BigDecimal feeRate) {
        this.feeRate = feeRate;
        return this;
    }

    public ScenarioRequestBuilder withIncludeDepressionYears(@Nullable Boolean includeDepressionYears) {
        this.includeDepressionYears = includeDepressionYears;
        return this;
    }

    public ScenarioRequestBuilder withInterestYield(@Nullable BigDecimal interestYield) {
        this.interestYield = interestYield;
        return this;
    }

    public ScenarioRequestBuilder withSpouseBirthYear(@Nullable Integer spouseBirthYear) {
        this.spouseBirthYear = spouseBirthYear;
        return this;
    }

    public ScenarioRequestBuilder withPrimaryDeathAge(@Nullable Integer primaryDeathAge) {
        this.primaryDeathAge = primaryDeathAge;
        return this;
    }

    public ScenarioRequestBuilder withSpouseDeathAge(@Nullable Integer spouseDeathAge) {
        this.spouseDeathAge = spouseDeathAge;
        return this;
    }

    public ScenarioRequestBuilder withSurvivorSpendingFactor(@Nullable BigDecimal survivorSpendingFactor) {
        this.survivorSpendingFactor = survivorSpendingFactor;
        return this;
    }

    public ScenarioRequestBuilder withCommunityProperty(@Nullable Boolean communityProperty) {
        this.communityProperty = communityProperty;
        return this;
    }

    public ScenarioRequestBuilder withStochasticMortality(@Nullable Boolean stochasticMortality) {
        this.stochasticMortality = stochasticMortality;
        return this;
    }

    public ScenarioRequestBuilder withPrimarySex(@Nullable String primarySex) {
        this.primarySex = primarySex;
        return this;
    }

    public ScenarioRequestBuilder withSpouseSex(@Nullable String spouseSex) {
        this.spouseSex = spouseSex;
        return this;
    }

    public ScenarioRequestBuilder withLongevityConditionalAge(@Nullable Integer longevityConditionalAge) {
        this.longevityConditionalAge = longevityConditionalAge;
        return this;
    }

    public ScenarioRequestBuilder withAccounts(List<CreateProjectionAccountRequest> accounts) {
        this.accounts = accounts;
        return this;
    }

    public ScenarioRequestBuilder withIncomeSources(@Nullable List<ScenarioIncomeSourceInput> incomeSources) {
        this.incomeSources = incomeSources;
        return this;
    }

    public ScenarioRequest build() {
        return new ScenarioRequest(
                name, retirementDate, endAge, inflationRate, birthYear, withdrawalRate, withdrawalStrategy,
                dynamicCeiling, dynamicFloor, filingStatus, null, null, withdrawalOrder, null,
                rothConversionStrategy, targetBracketRate, null, null, null, null,
                dividendYield, feeRate, includeDepressionYears, interestYield,
                spouseBirthYear, primaryDeathAge, spouseDeathAge, survivorSpendingFactor, communityProperty,
                stochasticMortality, primarySex, spouseSex, longevityConditionalAge,
                accounts, null, null, incomeSources);
    }
}
