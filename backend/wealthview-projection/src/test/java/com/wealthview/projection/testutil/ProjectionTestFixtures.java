package com.wealthview.projection.testutil;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.DeterministicProjectionEngine;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;

public final class ProjectionTestFixtures {

    private ProjectionTestFixtures() {}

    public static HypotheticalAccountInput acct(String balance, String contribution, String expectedReturn) {
        return new HypotheticalAccountInput(bd(balance), bd(contribution), bd(expectedReturn), "taxable");
    }

    public static HypotheticalAccountInput acct(String balance, String contribution, String expectedReturn, String type) {
        return new HypotheticalAccountInput(bd(balance), bd(contribution), bd(expectedReturn), type);
    }

    public static ProjectionIncomeSourceInput incomeSource(String name, String amount,
                                                            int startAge, Integer endAge,
                                                            String inflationRate) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, "other",
                bd(amount), startAge, endAge, bd(inflationRate), false,
                "taxable",
                null, null, null, null, null, null);
    }

    public static ProjectionIncomeSourceInput oneTimeIncomeSource(String name, String amount,
                                                                    int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, "other",
                bd(amount), startAge, startAge + 1, bd("0"), true,
                "taxable",
                null, null, null, null, null, null);
    }

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts) {
        return createInput(retDate, endAge, inflation, paramsJson, accounts, null, List.of());
    }

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts,
                                               SpendingProfileInput spendingProfile) {
        return createInput(retDate, endAge, inflation, paramsJson, accounts, spendingProfile, List.of());
    }

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts,
                                               SpendingProfileInput spendingProfile,
                                               List<ProjectionIncomeSourceInput> incomeSources) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, spendingProfile,
                null, incomeSources);
    }

    public static ProjectionInput createInputWithProperties(LocalDate retDate, int endAge,
                                                              BigDecimal inflation, String paramsJson,
                                                              List<ProjectionAccountInput> accounts,
                                                              List<ProjectionPropertyInput> properties) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, null,
                null, List.of(), null, properties);
    }

    public static ProjectionPropertyInput property(String currentValue, String appreciationRate,
                                                     String loanAmount, String interestRate,
                                                     int termMonths, LocalDate loanStartDate) {
        return new ProjectionPropertyInput(
                UUID.randomUUID(), "Test Property",
                bd(currentValue), bd(loanAmount),
                bd(appreciationRate),
                bd(loanAmount), bd(interestRate), termMonths, loanStartDate);
    }

    public static ProjectionPropertyInput propertyNoLoan(String currentValue, String appreciationRate,
                                                           String mortgageBalance) {
        return new ProjectionPropertyInput(
                UUID.randomUUID(), "Test Property",
                bd(currentValue), bd(mortgageBalance),
                bd(appreciationRate),
                null, null, 0, null);
    }

    public static ProjectionIncomeSourceInput socialSecuritySource(String amount, int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Social Security", "social_security",
                bd(amount), startAge, null, bd("0"), false,
                "taxable",
                null, null, null, null, null, null);
    }

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts,
                                               SpendingProfileInput spendingProfile,
                                               List<ProjectionIncomeSourceInput> incomeSources,
                                               List<ProjectionPropertyInput> properties) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, spendingProfile,
                null, incomeSources, null, properties);
    }

    public static ProjectionInput createRetiredInput(String paramsJson,
                                                      List<ProjectionAccountInput> accounts) {
        return new ProjectionInput(UUID.randomUUID(), "Test",
                LocalDate.now().minusYears(1), 95, BigDecimal.ZERO, paramsJson,
                accounts, null);
    }

    public static DeterministicProjectionEngine engineWithTax(TaxBracketRepository taxBracketRepo,
                                                                StandardDeductionRepository deductionRepo) {
        var calc = new FederalTaxCalculator(taxBracketRepo, deductionRepo);
        return new DeterministicProjectionEngine(calc, null);
    }
}
