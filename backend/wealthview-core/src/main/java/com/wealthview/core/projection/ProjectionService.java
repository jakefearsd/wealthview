package com.wealthview.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wealthview.core.account.AccountService;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.core.projection.dto.LinkedAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionAccountResponse;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.core.property.DepreciationCalculator;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.PropertyDepreciationScheduleRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final SpendingProfileRepository spendingProfileRepository;
    private final ProjectionEngine projectionEngine;
    private final AccountService accountService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final PropertyDepreciationScheduleRepository depreciationScheduleRepository;
    private final DepreciationCalculator depreciationCalculator;
    private final IncomeSourceRepository incomeSourceRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectionService(ProjectionScenarioRepository scenarioRepository,
                             TenantRepository tenantRepository,
                             AccountRepository accountRepository,
                             SpendingProfileRepository spendingProfileRepository,
                             ProjectionEngine projectionEngine,
                             AccountService accountService,
                             ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                             PropertyDepreciationScheduleRepository depreciationScheduleRepository,
                             DepreciationCalculator depreciationCalculator,
                             IncomeSourceRepository incomeSourceRepository) {
        this.scenarioRepository = scenarioRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.spendingProfileRepository = spendingProfileRepository;
        this.projectionEngine = projectionEngine;
        this.accountService = accountService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.depreciationScheduleRepository = depreciationScheduleRepository;
        this.depreciationCalculator = depreciationCalculator;
        this.incomeSourceRepository = incomeSourceRepository;
    }

    @Transactional
    public ScenarioResponse createScenario(UUID tenantId, CreateScenarioRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        String paramsJson = buildParamsJson(
                request.birthYear(), request.withdrawalRate(), request.withdrawalStrategy(),
                request.dynamicCeiling(), request.dynamicFloor(), request.filingStatus(),
                request.otherIncome(), request.annualRothConversion(), request.withdrawalOrder(),
                request.rothConversionStrategy(), request.targetBracketRate(),
                request.rothConversionStartYear());

        var scenario = new ProjectionScenarioEntity(
                tenant, request.name(), request.retirementDate(),
                request.endAge(), request.inflationRate(), paramsJson);

        if (request.spendingProfileId() != null) {
            var profile = spendingProfileRepository.findByTenant_IdAndId(tenantId, request.spendingProfileId())
                    .orElse(null);
            scenario.setSpendingProfile(profile);
        }

        if (request.accounts() != null) {
            for (var acctReq : request.accounts()) {
                var linkedAccount = acctReq.linkedAccountId() != null
                        ? accountRepository.findByTenant_IdAndId(tenantId, acctReq.linkedAccountId())
                                .orElse(null)
                        : null;

                var projAcct = new ProjectionAccountEntity(
                        scenario, linkedAccount,
                        linkedAccount != null ? null : acctReq.initialBalance(),
                        acctReq.annualContribution(),
                        acctReq.expectedReturn(),
                        acctReq.accountType());
                scenario.addAccount(projAcct);
            }
        }

        var saved = scenarioRepository.save(scenario);

        saveIncomeSourceLinks(saved, tenantId, request.incomeSources());

        log.info("Scenario '{}' created with {} accounts for tenant {}",
                request.name(), request.accounts() != null ? request.accounts().size() : 0, tenantId);
        return toScenarioResponse(saved, tenantId);
    }

    @Transactional(readOnly = true)
    public ScenarioResponse getScenario(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        return toScenarioResponse(scenario, tenantId);
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> listScenarios(UUID tenantId) {
        return scenarioRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .map(s -> toScenarioResponse(s, tenantId))
                .toList();
    }

    @Transactional
    public ScenarioResponse updateScenario(UUID tenantId, UUID scenarioId, UpdateScenarioRequest request) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));

        scenario.setName(request.name());
        scenario.setRetirementDate(request.retirementDate());
        scenario.setEndAge(request.endAge());
        scenario.setInflationRate(request.inflationRate());
        scenario.setParamsJson(buildParamsJson(
                request.birthYear(), request.withdrawalRate(), request.withdrawalStrategy(),
                request.dynamicCeiling(), request.dynamicFloor(), request.filingStatus(),
                request.otherIncome(), request.annualRothConversion(), request.withdrawalOrder(),
                request.rothConversionStrategy(), request.targetBracketRate(),
                request.rothConversionStartYear()));
        scenario.setUpdatedAt(OffsetDateTime.now());

        if (request.spendingProfileId() != null) {
            var profile = spendingProfileRepository.findByTenant_IdAndId(tenantId, request.spendingProfileId())
                    .orElse(null);
            scenario.setSpendingProfile(profile);
        } else {
            scenario.setSpendingProfile(null);
        }

        scenario.getAccounts().clear();
        if (request.accounts() != null) {
            for (var acctReq : request.accounts()) {
                var linkedAccount = acctReq.linkedAccountId() != null
                        ? accountRepository.findByTenant_IdAndId(tenantId, acctReq.linkedAccountId())
                                .orElse(null)
                        : null;
                var projAcct = new ProjectionAccountEntity(
                        scenario, linkedAccount,
                        linkedAccount != null ? null : acctReq.initialBalance(),
                        acctReq.annualContribution(),
                        acctReq.expectedReturn(),
                        acctReq.accountType());
                scenario.addAccount(projAcct);
            }
        }

        scenarioIncomeSourceRepository.deleteByScenario_Id(scenarioId);
        saveIncomeSourceLinks(scenario, tenantId, request.incomeSources());

        var saved = scenarioRepository.save(scenario);
        log.info("Scenario {} updated for tenant {}", scenarioId, tenantId);
        return toScenarioResponse(saved, tenantId);
    }

    @Transactional
    public void deleteScenario(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        scenarioRepository.delete(scenario);
        log.info("Scenario {} deleted for tenant {}", scenarioId, tenantId);
    }

    @Transactional(readOnly = true)
    public CompareResponse compareScenarios(UUID tenantId, CompareRequest request) {
        log.info("Comparing {} scenarios for tenant {}", request.scenarioIds().size(), tenantId);
        var results = new java.util.ArrayList<ProjectionResultResponse>();
        for (var scenarioId : request.scenarioIds()) {
            var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                    .orElseThrow(() -> new EntityNotFoundException("Scenario not found: " + scenarioId));
            results.add(projectionEngine.run(toProjectionInput(scenario, tenantId)));
        }
        return new CompareResponse(results);
    }

    @Transactional(readOnly = true)
    public ProjectionResultResponse runProjection(UUID tenantId, UUID scenarioId) {
        log.info("Running projection for scenario {} tenant {}", scenarioId, tenantId);
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        return projectionEngine.run(toProjectionInput(scenario, tenantId));
    }

    private ProjectionInput toProjectionInput(ProjectionScenarioEntity scenario, UUID tenantId) {
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

    private ScenarioResponse toScenarioResponse(ProjectionScenarioEntity scenario, UUID tenantId) {
        var accounts = scenario.getAccounts().stream()
                .map(acct -> {
                    var balance = acct.getLinkedAccount() != null
                            ? accountService.computeBalance(acct.getLinkedAccount(), tenantId)
                            : acct.getInitialBalance();
                    return new ProjectionAccountResponse(
                            acct.getId(),
                            acct.getLinkedAccount() != null ? acct.getLinkedAccount().getId() : null,
                            balance,
                            acct.getAnnualContribution(),
                            acct.getExpectedReturn(),
                            acct.getAccountType());
                })
                .toList();
        var profile = scenario.getSpendingProfile() != null
                ? SpendingProfileResponse.from(scenario.getSpendingProfile())
                : null;
        var incomeSources = scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()).stream()
                .map(link -> {
                    var src = link.getIncomeSource();
                    var effective = link.getOverrideAnnualAmount() != null
                            ? link.getOverrideAnnualAmount() : src.getAnnualAmount();
                    return new ScenarioIncomeSourceResponse(
                            src.getId(), src.getName(), src.getIncomeType(),
                            src.getAnnualAmount(), link.getOverrideAnnualAmount(), effective,
                            src.getStartAge(), src.getEndAge(),
                            src.getInflationRate(), src.isOneTime());
                })
                .toList();
        return new ScenarioResponse(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, profile, incomeSources, scenario.getCreatedAt(), scenario.getUpdatedAt());
    }

    private void saveIncomeSourceLinks(ProjectionScenarioEntity scenario, UUID tenantId,
                                       java.util.List<com.wealthview.core.projection.dto.ScenarioIncomeSourceInput> incomeSources) {
        if (incomeSources == null) {
            return;
        }
        for (var isReq : incomeSources) {
            var incomeSource = incomeSourceRepository.findByTenant_IdAndId(tenantId, isReq.incomeSourceId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "Income source not found: " + isReq.incomeSourceId()));
            scenarioIncomeSourceRepository.save(
                    new ScenarioIncomeSourceEntity(scenario, incomeSource, isReq.overrideAnnualAmount()));
        }
    }

    private String buildParamsJson(Integer birthYear, BigDecimal withdrawalRate,
                                     String withdrawalStrategy, BigDecimal dynamicCeiling,
                                     BigDecimal dynamicFloor, String filingStatus,
                                     BigDecimal otherIncome, BigDecimal annualRothConversion,
                                     String withdrawalOrder,
                                     String rothConversionStrategy, BigDecimal targetBracketRate,
                                     Integer rothConversionStartYear) {
        ObjectNode node = objectMapper.createObjectNode();
        boolean hasContent = false;
        hasContent |= putIfNotNull(node, "birth_year", birthYear);
        hasContent |= putIfNotNull(node, "withdrawal_rate", withdrawalRate);
        hasContent |= putIfNotNull(node, "withdrawal_strategy", withdrawalStrategy);
        hasContent |= putIfNotNull(node, "dynamic_ceiling", dynamicCeiling);
        hasContent |= putIfNotNull(node, "dynamic_floor", dynamicFloor);
        hasContent |= putIfNotNull(node, "filing_status", filingStatus);
        hasContent |= putIfNotNull(node, "other_income", otherIncome);
        hasContent |= putIfNotNull(node, "annual_roth_conversion", annualRothConversion);
        hasContent |= putIfNotNull(node, "withdrawal_order", withdrawalOrder);
        hasContent |= putIfNotNull(node, "roth_conversion_strategy", rothConversionStrategy);
        hasContent |= putIfNotNull(node, "target_bracket_rate", targetBracketRate);
        hasContent |= putIfNotNull(node, "roth_conversion_start_year", rothConversionStartYear);
        return hasContent ? node.toString() : null;
    }

    private boolean putIfNotNull(ObjectNode node, String key, Object value) {
        if (value == null) return false;
        if (value instanceof Integer i) node.put(key, i);
        else if (value instanceof BigDecimal bd) node.put(key, bd);
        else if (value instanceof String s) node.put(key, s);
        return true;
    }
}
