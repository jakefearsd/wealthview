package com.wealthview.projection.testutil;

import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.tax.FederalTaxCalculator;
import com.wealthview.core.testutil.TaxBracketFixtures;
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

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts) {
        return createInput(retDate, endAge, inflation, paramsJson, accounts, null);
    }

    public static ProjectionInput createInput(LocalDate retDate, int endAge,
                                               BigDecimal inflation, String paramsJson,
                                               List<ProjectionAccountInput> accounts,
                                               SpendingProfileInput spendingProfile) {
        return new ProjectionInput(UUID.randomUUID(), "Test Scenario",
                retDate, endAge, inflation, paramsJson, accounts, spendingProfile);
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
        return new DeterministicProjectionEngine(calc);
    }
}
