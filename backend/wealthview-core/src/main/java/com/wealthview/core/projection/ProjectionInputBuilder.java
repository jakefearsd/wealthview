package com.wealthview.core.projection;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.LinkedAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.property.AmortizationCalculator;
import com.wealthview.core.property.DepreciationCalculator;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.PropertyDepreciationScheduleRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectionInputBuilder {

    private final AccountService accountService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final PropertyDepreciationScheduleRepository depreciationScheduleRepository;
    private final DepreciationCalculator depreciationCalculator;

    public ProjectionInputBuilder(AccountService accountService,
                                  ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                                  PropertyDepreciationScheduleRepository depreciationScheduleRepository,
                                  DepreciationCalculator depreciationCalculator) {
        this.accountService = accountService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.depreciationScheduleRepository = depreciationScheduleRepository;
        this.depreciationCalculator = depreciationCalculator;
    }

    public ProjectionInput build(ProjectionScenarioEntity scenario, UUID tenantId) {
        var accounts = resolveAccounts(scenario, tenantId);
        var spendingProfile = scenario.getSpendingProfile() != null
                ? new SpendingProfileInput(
                        scenario.getSpendingProfile().getEssentialExpenses(),
                        scenario.getSpendingProfile().getDiscretionaryExpenses(),
                        scenario.getSpendingProfile().getSpendingTiers())
                : null;
        var incomeSources = resolveIncomeSources(scenario.getId());
        return new ProjectionInput(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, spendingProfile, null, incomeSources);
    }

    private List<ProjectionAccountInput> resolveAccounts(ProjectionScenarioEntity scenario, UUID tenantId) {
        return scenario.getAccounts().stream()
                .map(entity -> toAccountInput(entity, tenantId))
                .toList();
    }

    private ProjectionAccountInput toAccountInput(ProjectionAccountEntity entity, UUID tenantId) {
        if (entity.getLinkedAccount() != null) {
            var liveBalance = accountService.computeBalance(entity.getLinkedAccount(), tenantId);
            return new LinkedAccountInput(
                    entity.getLinkedAccount().getId(), liveBalance,
                    entity.getAnnualContribution(), entity.getExpectedReturn(),
                    entity.getAccountType());
        }
        return new HypotheticalAccountInput(
                entity.getInitialBalance(),
                entity.getAnnualContribution(), entity.getExpectedReturn(),
                entity.getAccountType());
    }

    private List<ProjectionIncomeSourceInput> resolveIncomeSources(UUID scenarioId) {
        var scenarioLinks = scenarioIncomeSourceRepository.findByScenario_Id(scenarioId);
        if (scenarioLinks.isEmpty()) {
            return List.of();
        }
        return scenarioLinks.stream()
                .map(link -> {
                    var source = link.getIncomeSource();
                    var amount = link.getOverrideAnnualAmount() != null
                            ? link.getOverrideAnnualAmount() : source.getAnnualAmount();
                    return toIncomeSourceInput(source, amount);
                })
                .toList();
    }

    private ProjectionIncomeSourceInput toIncomeSourceInput(IncomeSourceEntity source, BigDecimal amount) {
        BigDecimal annualOpEx = null;
        BigDecimal annualMortgageInterest = null;
        BigDecimal annualPropertyTax = null;
        String depreciationMethod = null;
        Map<Integer, BigDecimal> depreciationByYear = null;

        if ("rental_property".equals(source.getIncomeType()) && source.getProperty() != null) {
            var property = source.getProperty();

            annualOpEx = sumNullable(property.getAnnualInsuranceCost(), property.getAnnualMaintenanceCost());
            annualPropertyTax = property.getAnnualPropertyTax();

            if (property.hasLoanDetails()) {
                var remainingBalance = AmortizationCalculator.remainingBalance(
                        property.getLoanAmount(), property.getAnnualInterestRate(),
                        property.getLoanTermMonths(), property.getLoanStartDate(), LocalDate.now());
                if (remainingBalance.compareTo(BigDecimal.ZERO) > 0) {
                    annualMortgageInterest = remainingBalance.multiply(property.getAnnualInterestRate())
                            .setScale(4, RoundingMode.HALF_UP);
                }
            }

            depreciationMethod = property.getDepreciationMethod();

            if ("cost_segregation".equals(depreciationMethod)) {
                var scheduleEntries = depreciationScheduleRepository
                        .findByProperty_IdOrderByTaxYear(property.getId());
                depreciationByYear = new HashMap<>();
                for (var entry : scheduleEntries) {
                    depreciationByYear.put(entry.getTaxYear(), entry.getDepreciationAmount());
                }
            } else if ("straight_line".equals(depreciationMethod)
                    && property.getInServiceDate() != null) {
                var landValue = property.getLandValue() != null ? property.getLandValue() : BigDecimal.ZERO;
                depreciationByYear = depreciationCalculator.computeStraightLine(
                        property.getPurchasePrice(), landValue,
                        property.getInServiceDate(), property.getUsefulLifeYears());
            }
        }

        return new ProjectionIncomeSourceInput(
                source.getId(), source.getName(), source.getIncomeType(),
                amount, source.getStartAge(), source.getEndAge(),
                source.getInflationRate(), source.isOneTime(), source.getTaxTreatment(),
                annualOpEx, annualMortgageInterest, annualPropertyTax,
                depreciationMethod, depreciationByYear);
    }

    private BigDecimal sumNullable(BigDecimal... values) {
        var sum = BigDecimal.ZERO;
        for (var v : values) {
            if (v != null) {
                sum = sum.add(v);
            }
        }
        return sum.compareTo(BigDecimal.ZERO) == 0 ? null : sum;
    }
}
