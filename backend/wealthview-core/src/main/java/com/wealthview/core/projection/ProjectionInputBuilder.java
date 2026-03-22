package com.wealthview.core.projection;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.LinkedAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.projection.dto.ConversionYearDetail;
import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.RothConversionScheduleResponse;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.property.AmortizationCalculator;
import com.wealthview.core.property.DepreciationCalculator;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectionInputBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectionInputBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AccountService accountService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final DepreciationCalculator depreciationCalculator;
    private final GuardrailSpendingProfileRepository guardrailRepository;
    private final PropertyRepository propertyRepository;

    public ProjectionInputBuilder(AccountService accountService,
                                  ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                                  DepreciationCalculator depreciationCalculator,
                                  GuardrailSpendingProfileRepository guardrailRepository,
                                  PropertyRepository propertyRepository) {
        this.accountService = accountService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.depreciationCalculator = depreciationCalculator;
        this.guardrailRepository = guardrailRepository;
        this.propertyRepository = propertyRepository;
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
        var guardrailSpending = resolveGuardrailSpending(scenario);
        var properties = resolveProperties(tenantId);
        return new ProjectionInput(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, spendingProfile, null, incomeSources, guardrailSpending, properties);
    }

    private List<ProjectionPropertyInput> resolveProperties(UUID tenantId) {
        var entities = propertyRepository.findByTenant_Id(tenantId);
        return entities.stream()
                .map(this::toPropertyInput)
                .toList();
    }

    private ProjectionPropertyInput toPropertyInput(PropertyEntity property) {
        BigDecimal mortgageBalance;
        if (property.hasLoanDetails()) {
            mortgageBalance = AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(), property.getAnnualInterestRate(),
                    property.getLoanTermMonths(), property.getLoanStartDate(),
                    LocalDate.now());
        } else {
            mortgageBalance = property.getMortgageBalance();
        }

        return new ProjectionPropertyInput(
                property.getId(),
                property.getAddress(),
                property.getCurrentValue(),
                mortgageBalance,
                property.getAnnualAppreciationRate() != null
                        ? property.getAnnualAppreciationRate() : BigDecimal.ZERO,
                property.hasLoanDetails() ? property.getLoanAmount() : null,
                property.hasLoanDetails() ? property.getAnnualInterestRate() : null,
                property.hasLoanDetails() ? property.getLoanTermMonths() : 0,
                property.hasLoanDetails() ? property.getLoanStartDate() : null);
    }

    private GuardrailSpendingInput resolveGuardrailSpending(ProjectionScenarioEntity scenario) {
        if (scenario.getGuardrailProfile() == null) {
            return null;
        }
        var profile = guardrailRepository.findByScenario_Id(scenario.getId())
                .orElse(null);
        if (profile == null) {
            return null;
        }
        try {
            var yearlySpending = MAPPER.readValue(profile.getYearlySpending(),
                    new TypeReference<List<GuardrailYearlySpending>>() {});

            Map<Integer, BigDecimal> conversionByYear = null;
            if (profile.getConversionSchedule() != null && !profile.getConversionSchedule().isBlank()) {
                var scheduleResponse = MAPPER.readValue(profile.getConversionSchedule(),
                        RothConversionScheduleResponse.class);
                if (scheduleResponse.years() != null && !scheduleResponse.years().isEmpty()) {
                    conversionByYear = new HashMap<>();
                    for (var detail : scheduleResponse.years()) {
                        conversionByYear.put(detail.calendarYear(), detail.conversionAmount());
                    }
                }
            }

            return new GuardrailSpendingInput(yearlySpending, conversionByYear);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to parse guardrail yearly_spending", e);
            return null;
        }
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
        BigDecimal annualMortgagePrincipal = null;
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
                    BigDecimal annualPayment = AmortizationCalculator.monthlyPayment(
                            property.getLoanAmount(), property.getAnnualInterestRate(),
                            property.getLoanTermMonths())
                            .multiply(new BigDecimal("12"));
                    annualMortgagePrincipal = annualPayment.subtract(annualMortgageInterest)
                            .max(BigDecimal.ZERO);
                }
            }

            depreciationMethod = property.getDepreciationMethod();

            if ("cost_segregation".equals(depreciationMethod)) {
                var allocations = parseCostSegAllocations(property.getCostSegAllocations());
                if (!allocations.isEmpty()) {
                    depreciationByYear = depreciationCalculator.computeCostSegregation(
                            allocations, property.getBonusDepreciationRate(),
                            property.getInServiceDate(), property.getCostSegStudyYear());
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
                annualOpEx, annualMortgageInterest, annualMortgagePrincipal, annualPropertyTax,
                depreciationMethod, depreciationByYear);
    }

    private List<com.wealthview.core.property.dto.CostSegAllocation> parseCostSegAllocations(String json) {
        if (json == null || json.isBlank() || "[]".equals(json)) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<com.wealthview.core.property.dto.CostSegAllocation>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("Failed to parse cost_seg_allocations JSON", e);
            return List.of();
        }
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
