package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.common.Money;
import com.wealthview.core.exchangerate.ExchangeRateService;
import com.wealthview.core.projection.dto.GuardrailSpendingInput;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.IncomeSourceType;
import com.wealthview.core.projection.dto.LinkedAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionPropertyInput;
import com.wealthview.core.projection.dto.RothConversionScheduleResponse;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.property.DepreciationCalculator;
import com.wealthview.core.property.PropertyFinance;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.PropertyRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProjectionInputBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectionInputBuilder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AccountService accountService;
    private final ExchangeRateService exchangeRateService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final DepreciationCalculator depreciationCalculator;
    private final GuardrailSpendingProfileRepository guardrailRepository;
    private final PropertyRepository propertyRepository;

    public ProjectionInputBuilder(AccountService accountService,
                                  ExchangeRateService exchangeRateService,
                                  ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                                  DepreciationCalculator depreciationCalculator,
                                  GuardrailSpendingProfileRepository guardrailRepository,
                                  PropertyRepository propertyRepository) {
        this.accountService = accountService;
        this.exchangeRateService = exchangeRateService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.depreciationCalculator = depreciationCalculator;
        this.guardrailRepository = guardrailRepository;
        this.propertyRepository = propertyRepository;
    }

    public ProjectionInput build(ProjectionScenarioEntity scenario, UUID tenantId) {
        var accounts        = resolveAccounts(scenario, tenantId);
        var spendingProfile = resolveSpendingProfile(scenario);
        var incomeSources   = resolveIncomeSources(scenario.getId());
        var guardrailSpending = resolveGuardrailSpending(scenario);
        var properties      = resolveProperties(tenantId);
        return new ProjectionInput(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, spendingProfile, null, incomeSources, guardrailSpending, properties);
    }

    private SpendingProfileInput resolveSpendingProfile(ProjectionScenarioEntity scenario) {
        var profile = scenario.getSpendingProfile();
        if (profile == null) {
            return null;
        }
        return new SpendingProfileInput(
                profile.getEssentialExpenses(),
                profile.getDiscretionaryExpenses(),
                profile.getSpendingTiers());
    }

    private List<ProjectionPropertyInput> resolveProperties(UUID tenantId) {
        var entities = propertyRepository.findByTenant_Id(tenantId);
        return entities.stream()
                .map(this::toPropertyInput)
                .toList();
    }

    private ProjectionPropertyInput toPropertyInput(PropertyEntity property) {
        var mortgageBalance = PropertyFinance.mortgageBalanceAsOf(property, LocalDate.now());

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
        } catch (tools.jackson.core.JacksonException e) {
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
            var nativeBalance = accountService.computeBalance(entity.getLinkedAccount(), tenantId);
            var liveBalance = exchangeRateService.convertToUsd(
                    nativeBalance, entity.getLinkedAccount().getCurrency(), tenantId);
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

            annualOpEx = Money.nullIfZero(Money.sum(
                    property.getAnnualInsuranceCost(), property.getAnnualMaintenanceCost()));
            annualPropertyTax = property.getAnnualPropertyTax();

            var debtService = PropertyFinance.annualDebtService(property, LocalDate.now());
            if (debtService.isPresent()) {
                annualMortgageInterest = debtService.get().interest();
                annualMortgagePrincipal = debtService.get().principal();
            }

            depreciationMethod = property.getDepreciationMethod();

            var schedule = depreciationCalculator.computeSchedule(property);
            if (!schedule.isEmpty()) {
                depreciationByYear = schedule;
            }
        }

        return new ProjectionIncomeSourceInput(
                source.getId(), source.getName(), IncomeSourceType.fromString(source.getIncomeType()),
                amount, source.getStartAge(), source.getEndAge(),
                source.getInflationRate(), source.isOneTime(), source.getTaxTreatment(),
                annualOpEx, annualMortgageInterest, annualMortgagePrincipal, annualPropertyTax,
                depreciationMethod, depreciationByYear);
    }

}
