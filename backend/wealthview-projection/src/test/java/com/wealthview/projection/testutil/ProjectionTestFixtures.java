package com.wealthview.projection.testutil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.wealthview.core.projection.CapitalMarketAssumptionsProvider.RealReturnMatrix;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.projection.tax.IrmaaSurchargeCalculator;
import com.wealthview.persistence.repository.IrmaaTierRepository;
import com.wealthview.persistence.repository.StandardDeductionRepository;
import com.wealthview.persistence.repository.TaxBracketRepository;
import com.wealthview.projection.DeterministicProjectionEngine;

import static com.wealthview.core.testutil.TaxBracketFixtures.bd;

public final class ProjectionTestFixtures {

    private ProjectionTestFixtures() {}

    /**
     * A small hand-built capital-market {@link RealReturnMatrix} for Monte Carlo unit tests:
     * 8 synthetic years x 4 asset classes of real returns with realistic dispersion (US stocks
     * range -20% to +30%, positive long-run mean; bonds/cash muted). Shared across MC tests so a
     * single fixture drives the block-bootstrap fan. Columns follow {@link AssetClass#values()}
     * order: US_STOCK, INTL_STOCK, BOND, CASH.
     */
    public static final RealReturnMatrix TEST_CMA_MATRIX = new RealReturnMatrix(
            new int[]{1, 2, 3, 4, 5, 6, 7, 8},
            AssetClass.values(),
            new double[][]{
                    { 0.18,  0.15,  0.03,  0.005},
                    {-0.12, -0.15,  0.06,  0.010},
                    { 0.25,  0.20,  0.01, -0.005},
                    { 0.08,  0.05,  0.04,  0.008},
                    {-0.20, -0.18, -0.02,  0.002},
                    { 0.15,  0.12,  0.05,  0.006},
                    { 0.30,  0.25,  0.00, -0.010},
                    { 0.02, -0.03,  0.07,  0.012},
            });

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
                UUID.randomUUID(), name, IncomeSourceType.OTHER,
                bd(amount), startAge, endAge, bd(inflationRate), false,
                "taxable",
                null, null, null, null, null, null);
    }

    public static ProjectionIncomeSourceInput oneTimeIncomeSource(String name, String amount,
                                                                    int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, IncomeSourceType.OTHER,
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

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts,
                                               SpendingProfileInput spendingProfile,
                                               Integer referenceYear,
                                               List<ProjectionIncomeSourceInput> incomeSources) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, spendingProfile,
                referenceYear, incomeSources);
    }

    public static ProjectionInput createGuardrailInput(LocalDate retDate, int endAge,
                                                        BigDecimal inflation, String paramsJson,
                                                        List<ProjectionAccountInput> accounts,
                                                        GuardrailSpendingInput guardrailSpending) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, null,
                null, List.of(), guardrailSpending);
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

    public static ProjectionIncomeSourceInput selfEmploymentSource(String name, String amount,
                                                                      int startAge, Integer endAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), name, IncomeSourceType.PART_TIME_WORK,
                bd(amount), startAge, endAge, bd("0"), false,
                "self_employment",
                null, null, null, null, null, null);
    }

    public static ProjectionIncomeSourceInput socialSecuritySource(String amount, int startAge) {
        return new ProjectionIncomeSourceInput(
                UUID.randomUUID(), "Social Security", IncomeSourceType.SOCIAL_SECURITY,
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

    /** Like {@link #engineWithTax}, plus the Wave-4 IRMAA item's surcharge calculator. */
    public static DeterministicProjectionEngine engineWithTaxAndIrmaa(TaxBracketRepository taxBracketRepo,
                                                                        StandardDeductionRepository deductionRepo,
                                                                        IrmaaTierRepository irmaaTierRepo) {
        var calc = new FederalTaxCalculator(taxBracketRepo, deductionRepo);
        var irmaaCalc = new IrmaaSurchargeCalculator(irmaaTierRepo);
        return new DeterministicProjectionEngine(calc, null, null, irmaaCalc);
    }
}
