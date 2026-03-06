package com.wealthview.core.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CompareResponse;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectionService {

    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final ProjectionEngine projectionEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProjectionService(ProjectionScenarioRepository scenarioRepository,
                             TenantRepository tenantRepository,
                             AccountRepository accountRepository,
                             ProjectionEngine projectionEngine) {
        this.scenarioRepository = scenarioRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.projectionEngine = projectionEngine;
    }

    @Transactional
    public ScenarioResponse createScenario(UUID tenantId, CreateScenarioRequest request) {
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new EntityNotFoundException("Tenant not found"));

        String paramsJson = buildParamsJson(request);

        var scenario = new ProjectionScenarioEntity(
                tenant, request.name(), request.retirementDate(),
                request.endAge(), request.inflationRate(), paramsJson);

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
            results.add(projectionEngine.run(scenario));
        }
        return new CompareResponse(results);
    }

    @Transactional(readOnly = true)
    public ProjectionResultResponse runProjection(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));
        return projectionEngine.run(scenario);
    }

    private String buildParamsJson(CreateScenarioRequest request) {
        ObjectNode node = objectMapper.createObjectNode();
        boolean hasContent = false;
        if (request.birthYear() != null) {
            node.put("birth_year", request.birthYear());
            hasContent = true;
        }
        if (request.withdrawalRate() != null) {
            node.put("withdrawal_rate", request.withdrawalRate());
            hasContent = true;
        }
        if (request.withdrawalStrategy() != null) {
            node.put("withdrawal_strategy", request.withdrawalStrategy());
            hasContent = true;
        }
        if (request.dynamicCeiling() != null) {
            node.put("dynamic_ceiling", request.dynamicCeiling());
            hasContent = true;
        }
        if (request.dynamicFloor() != null) {
            node.put("dynamic_floor", request.dynamicFloor());
            hasContent = true;
        }
        if (request.filingStatus() != null) {
            node.put("filing_status", request.filingStatus());
            hasContent = true;
        }
        if (request.otherIncome() != null) {
            node.put("other_income", request.otherIncome());
            hasContent = true;
        }
        if (request.annualRothConversion() != null) {
            node.put("annual_roth_conversion", request.annualRothConversion());
            hasContent = true;
        }
        return hasContent ? node.toString() : null;
    }
}
