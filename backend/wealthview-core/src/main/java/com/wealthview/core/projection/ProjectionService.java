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
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.SpendingProfileInput;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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

        var saved = scenarioRepository.save(scenario);
        return toScenarioResponse(saved, tenantId);
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
            results.add(projectionEngine.run(toProjectionInput(scenario, tenantId)));
        }
        return new CompareResponse(results);
    }

    @Transactional(readOnly = true)
    public ProjectionResultResponse runProjection(UUID tenantId, UUID scenarioId) {
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
                        scenario.getSpendingProfile().getIncomeStreams(),
                        scenario.getSpendingProfile().getSpendingTiers())
                : null;
        return new ProjectionInput(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, spendingProfile);
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
        return new ScenarioResponse(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, profile, scenario.getCreatedAt(), scenario.getUpdatedAt());
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
        if (rothConversionStartYear != null) {
            node.put("roth_conversion_start_year", rothConversionStartYear);
            hasContent = true;
        }
        return hasContent ? node.toString() : null;
    }
}
