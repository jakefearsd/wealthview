package com.wealthview.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wealthview.core.account.AccountService;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectionService {

    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final SpendingProfileRepository spendingProfileRepository;
    private final ProjectionEngine projectionEngine;
    private final AccountService accountService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectionService(ProjectionScenarioRepository scenarioRepository,
                             TenantRepository tenantRepository,
                             AccountRepository accountRepository,
                             SpendingProfileRepository spendingProfileRepository,
                             ProjectionEngine projectionEngine,
                             AccountService accountService) {
        this.scenarioRepository = scenarioRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.spendingProfileRepository = spendingProfileRepository;
        this.projectionEngine = projectionEngine;
        this.accountService = accountService;
    }

    @Transactional
    public ScenarioResponse createScenario(UUID tenantId, CreateScenarioRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        String paramsJson = buildParamsJson(
                request.birthYear(), request.withdrawalRate(), request.withdrawalStrategy(),
                request.dynamicCeiling(), request.dynamicFloor(), request.filingStatus(),
                request.otherIncome(), request.annualRothConversion(), request.withdrawalOrder(),
                request.rothConversionStrategy(), request.targetBracketRate());

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
                        acctReq.initialBalance(),
                        acctReq.annualContribution(),
                        acctReq.expectedReturn(),
                        acctReq.accountType());
                scenario.addAccount(projAcct);
            }
        }

        var saved = scenarioRepository.save(scenario);
        return ScenarioResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public ScenarioResponse getScenario(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        return ScenarioResponse.from(scenario);
    }

    @Transactional(readOnly = true)
    public List<ScenarioResponse> listScenarios(UUID tenantId) {
        return scenarioRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId).stream()
                .map(ScenarioResponse::from)
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
                request.rothConversionStrategy(), request.targetBracketRate()));
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
                        acctReq.initialBalance(),
                        acctReq.annualContribution(),
                        acctReq.expectedReturn(),
                        acctReq.accountType());
                scenario.addAccount(projAcct);
            }
        }

        var saved = scenarioRepository.save(scenario);
        return ScenarioResponse.from(saved);
    }

    @Transactional
    public void deleteScenario(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        scenarioRepository.delete(scenario);
    }

    @Transactional(readOnly = true)
    public CompareResponse compareScenarios(UUID tenantId, CompareRequest request) {
        var results = new java.util.ArrayList<ProjectionResultResponse>();
        for (var scenarioId : request.scenarioIds()) {
            var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                    .orElseThrow(() -> new EntityNotFoundException("Scenario not found: " + scenarioId));
            resolveLinkedAccountBalances(scenario, tenantId);
            results.add(projectionEngine.run(scenario));
        }
        return new CompareResponse(results);
    }

    @Transactional(readOnly = true)
    public ProjectionResultResponse runProjection(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        resolveLinkedAccountBalances(scenario, tenantId);
        return projectionEngine.run(scenario);
    }

    private void resolveLinkedAccountBalances(ProjectionScenarioEntity scenario, UUID tenantId) {
        for (var projAcct : scenario.getAccounts()) {
            if (projAcct.getLinkedAccount() != null) {
                var currentBalance = accountService.computeBalance(projAcct.getLinkedAccount(), tenantId);
                projAcct.setInitialBalance(currentBalance);
            }
        }
    }

    private String buildParamsJson(Integer birthYear, java.math.BigDecimal withdrawalRate,
                                     String withdrawalStrategy, java.math.BigDecimal dynamicCeiling,
                                     java.math.BigDecimal dynamicFloor, String filingStatus,
                                     java.math.BigDecimal otherIncome, java.math.BigDecimal annualRothConversion,
                                     String withdrawalOrder,
                                     String rothConversionStrategy, java.math.BigDecimal targetBracketRate) {
        ObjectNode node = objectMapper.createObjectNode();
        boolean hasContent = false;
        if (birthYear != null) {
            node.put("birth_year", birthYear);
            hasContent = true;
        }
        if (withdrawalRate != null) {
            node.put("withdrawal_rate", withdrawalRate);
            hasContent = true;
        }
        if (withdrawalStrategy != null) {
            node.put("withdrawal_strategy", withdrawalStrategy);
            hasContent = true;
        }
        if (dynamicCeiling != null) {
            node.put("dynamic_ceiling", dynamicCeiling);
            hasContent = true;
        }
        if (dynamicFloor != null) {
            node.put("dynamic_floor", dynamicFloor);
            hasContent = true;
        }
        if (filingStatus != null) {
            node.put("filing_status", filingStatus);
            hasContent = true;
        }
        if (otherIncome != null) {
            node.put("other_income", otherIncome);
            hasContent = true;
        }
        if (annualRothConversion != null) {
            node.put("annual_roth_conversion", annualRothConversion);
            hasContent = true;
        }
        if (withdrawalOrder != null) {
            node.put("withdrawal_order", withdrawalOrder);
            hasContent = true;
        }
        if (rothConversionStrategy != null) {
            node.put("roth_conversion_strategy", rothConversionStrategy);
            hasContent = true;
        }
        if (targetBracketRate != null) {
            node.put("target_bracket_rate", targetBracketRate);
            hasContent = true;
        }
        return hasContent ? node.toString() : null;
    }
}
