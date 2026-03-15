package com.wealthview.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wealthview.core.account.AccountService;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.GuardrailProfileSummary;
import com.wealthview.core.projection.dto.ProjectionAccountResponse;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.IncomeSourceRepository;
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
import java.util.List;
import java.util.UUID;

@Service
@SuppressWarnings("PMD.CouplingBetweenObjects")
public class ProjectionService {

    private static final Logger log = LoggerFactory.getLogger(ProjectionService.class);

    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final SpendingProfileRepository spendingProfileRepository;
    private final ProjectionEngine projectionEngine;
    private final AccountService accountService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final ProjectionInputBuilder projectionInputBuilder;
    private final GuardrailSpendingProfileRepository guardrailProfileRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @SuppressWarnings("PMD.ExcessiveParameterList")
    public ProjectionService(ProjectionScenarioRepository scenarioRepository,
                             TenantRepository tenantRepository,
                             AccountRepository accountRepository,
                             SpendingProfileRepository spendingProfileRepository,
                             ProjectionEngine projectionEngine,
                             AccountService accountService,
                             ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                             IncomeSourceRepository incomeSourceRepository,
                             ProjectionInputBuilder projectionInputBuilder,
                             GuardrailSpendingProfileRepository guardrailProfileRepository) {
        this.scenarioRepository = scenarioRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.spendingProfileRepository = spendingProfileRepository;
        this.projectionEngine = projectionEngine;
        this.accountService = accountService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.projectionInputBuilder = projectionInputBuilder;
        this.guardrailProfileRepository = guardrailProfileRepository;
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
            scenario.setGuardrailProfile(null);
        } else if (Boolean.TRUE.equals(request.useGuardrailProfile())) {
            scenario.setSpendingProfile(null);
        } else {
            // No explicit spending plan selected — clear spending profile but preserve
            // any existing guardrail profile. Guardrail profiles are managed by the
            // optimizer (create/reoptimize/delete), not by the scenario edit form.
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

        guardrailProfileRepository.findByScenario_Id(scenarioId).ifPresent(profile -> {
            var newHash = GuardrailProfileService.computeScenarioHash(saved);
            if (!newHash.equals(profile.getScenarioHash())) {
                profile.setStale(true);
                profile.setUpdatedAt(OffsetDateTime.now());
                guardrailProfileRepository.save(profile);
                log.info("Guardrail profile marked stale for scenario {}", scenarioId);
            }
        });

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
            results.add(projectionEngine.run(projectionInputBuilder.build(scenario, tenantId)));
        }
        return new CompareResponse(results);
    }

    @Transactional(readOnly = true)
    public ProjectionResultResponse runProjection(UUID tenantId, UUID scenarioId) {
        log.info("Running projection for scenario {} tenant {}", scenarioId, tenantId);
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        return projectionEngine.run(projectionInputBuilder.build(scenario, tenantId));
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
        var guardrailEntity = guardrailProfileRepository.findByScenario_Id(scenario.getId()).orElse(null);
        var guardrail = guardrailEntity != null
                ? GuardrailProfileSummary.from(guardrailEntity, scenario.getGuardrailProfile() != null)
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
                accounts, profile, guardrail, incomeSources, scenario.getCreatedAt(), scenario.getUpdatedAt());
    }

    private void saveIncomeSourceLinks(ProjectionScenarioEntity scenario, UUID tenantId,
                                       List<com.wealthview.core.projection.dto.ScenarioIncomeSourceInput> incomeSources) {
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

    @SuppressWarnings("PMD.ExcessiveParameterList") // private helper assembling JSON from many fields
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
        if (value == null) {
            return false;
        }
        if (value instanceof Integer i) {
            node.put(key, i);
        } else if (value instanceof BigDecimal bd) {
            node.put(key, bd);
        } else if (value instanceof String s) {
            node.put(key, s);
        }
        return true;
    }
}
