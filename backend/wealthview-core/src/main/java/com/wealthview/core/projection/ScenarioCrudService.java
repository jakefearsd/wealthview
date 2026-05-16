package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wealthview.core.account.AccountService;
import com.wealthview.core.common.Entities;
import com.wealthview.core.common.Money;
import com.wealthview.core.exception.InvalidSessionException;
import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.GuardrailProfileSummary;
import com.wealthview.core.projection.dto.ProjectionAccountResponse;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceInput;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceResponse;
import com.wealthview.core.projection.dto.ScenarioParamsSource;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.core.property.AmortizationCalculator;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class ScenarioCrudService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCrudService.class);

    private static final int MIN_END_AGE = 50;
    private static final int MAX_END_AGE = 120;

    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantRepository tenantRepository;
    private final AccountRepository accountRepository;
    private final SpendingProfileRepository spendingProfileRepository;
    private final AccountService accountService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final GuardrailSpendingProfileRepository guardrailProfileRepository;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioCrudService(ProjectionScenarioRepository scenarioRepository,
                               TenantRepository tenantRepository,
                               AccountRepository accountRepository,
                               SpendingProfileRepository spendingProfileRepository,
                               AccountService accountService,
                               ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                               IncomeSourceRepository incomeSourceRepository,
                               GuardrailSpendingProfileRepository guardrailProfileRepository,
                               MeterRegistry meterRegistry) {
        this.scenarioRepository = scenarioRepository;
        this.tenantRepository = tenantRepository;
        this.accountRepository = accountRepository;
        this.spendingProfileRepository = spendingProfileRepository;
        this.accountService = accountService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.guardrailProfileRepository = guardrailProfileRepository;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ScenarioResponse createScenario(UUID tenantId, CreateScenarioRequest request) {
        validateEndAge(request.endAge());
        var tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new InvalidSessionException("Session expired — please log in again"));

        String paramsJson = buildParamsJson(extractParamsFromRequest(request));

        var scenario = new ProjectionScenarioEntity(
                tenant, request.name(), request.retirementDate(),
                request.endAge(), request.inflationRate(), paramsJson);

        if (request.spendingProfileId() != null) {
            var profile = spendingProfileRepository.findByTenant_IdAndId(tenantId, request.spendingProfileId())
                    .orElse(null);
            scenario.setSpendingProfile(profile);
        }

        addAccountsToScenario(scenario, tenantId, request.accounts());

        var saved = scenarioRepository.save(scenario);

        saveIncomeSourceLinks(saved, tenantId, request.incomeSources());

        meterRegistry.counter("wealthview.scenarios", "action", "create").increment();
        log.info("Scenario '{}' created with {} accounts for tenant {}",
                request.name(), request.accounts() != null ? request.accounts().size() : 0, tenantId);
        return toScenarioResponse(saved, tenantId);
    }

    @Transactional(readOnly = true)
    public ScenarioResponse getScenario(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario"));
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
        validateEndAge(request.endAge());
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario"));

        scenario.setName(request.name());
        scenario.setRetirementDate(request.retirementDate());
        scenario.setEndAge(request.endAge());
        scenario.setInflationRate(request.inflationRate());
        scenario.setParamsJson(buildParamsJson(extractParamsFromRequest(request)));
        scenario.setUpdatedAt(OffsetDateTime.now());

        if (request.spendingProfileId() != null) {
            var profile = spendingProfileRepository.findByTenant_IdAndId(tenantId, request.spendingProfileId())
                    .orElse(null);
            scenario.setSpendingProfile(profile);
            scenario.setGuardrailProfile(null);
        } else {
            // No spending profile selected — clear it. Preserve any existing guardrail
            // profile unless a new spending profile was explicitly chosen (handled above).
            // Guardrail profiles are managed by the optimizer, not the scenario edit form.
            scenario.setSpendingProfile(null);
        }

        scenario.getAccounts().clear();
        addAccountsToScenario(scenario, tenantId, request.accounts());

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

        meterRegistry.counter("wealthview.scenarios", "action", "update").increment();
        log.info("Scenario {} updated for tenant {}", scenarioId, tenantId);
        return toScenarioResponse(saved, tenantId);
    }

    @Transactional
    public void deleteScenario(UUID tenantId, UUID scenarioId) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario"));
        scenarioRepository.delete(scenario);
        meterRegistry.counter("wealthview.scenarios", "action", "delete").increment();
        log.info("Scenario {} deleted for tenant {}", scenarioId, tenantId);
    }

    private ScenarioResponse toScenarioResponse(ProjectionScenarioEntity scenario, UUID tenantId) {
        var accounts = mapAccounts(scenario, tenantId);
        var profile = scenario.getSpendingProfile() != null
                ? SpendingProfileResponse.from(scenario.getSpendingProfile())
                : null;
        var guardrail = mapGuardrailProfile(scenario);
        var incomeSources = mapIncomeSources(scenario);
        return new ScenarioResponse(
                scenario.getId(), scenario.getName(), scenario.getRetirementDate(),
                scenario.getEndAge(), scenario.getInflationRate(), scenario.getParamsJson(),
                accounts, profile, guardrail, incomeSources, scenario.getCreatedAt(), scenario.getUpdatedAt());
    }

    private List<ProjectionAccountResponse> mapAccounts(ProjectionScenarioEntity scenario, UUID tenantId) {
        return scenario.getAccounts().stream()
                .map(acct -> {
                    var balance = acct.getLinkedAccount() != null
                            ? accountService.computeBalance(acct.getLinkedAccount(), tenantId)
                            : acct.getInitialBalance();
                    return ProjectionAccountResponse.from(acct, balance);
                })
                .toList();
    }

    private GuardrailProfileSummary mapGuardrailProfile(ProjectionScenarioEntity scenario) {
        var guardrailEntity = guardrailProfileRepository.findByScenario_Id(scenario.getId()).orElse(null);
        return guardrailEntity != null
                ? GuardrailProfileSummary.from(guardrailEntity, scenario.getGuardrailProfile() != null)
                : null;
    }

    private List<ScenarioIncomeSourceResponse> mapIncomeSources(ProjectionScenarioEntity scenario) {
        return scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenario.getId()).stream()
                .map(link -> {
                    var src = link.getIncomeSource();
                    var effective = link.getOverrideAnnualAmount() != null
                            ? link.getOverrideAnnualAmount() : src.getAnnualAmount();
                    var netCashFlow = computeRentalNetCashFlow(src.getIncomeType(),
                            src.getProperty(), effective);
                    return ScenarioIncomeSourceResponse.from(link, effective, netCashFlow);
                })
                .toList();
    }

    private void saveIncomeSourceLinks(ProjectionScenarioEntity scenario, UUID tenantId,
                                       List<ScenarioIncomeSourceInput> incomeSources) {
        if (incomeSources == null) {
            return;
        }
        for (var isReq : incomeSources) {
            var incomeSource = incomeSourceRepository.findByTenant_IdAndId(tenantId, isReq.incomeSourceId())
                    .orElseThrow(Entities.notFound("Income source", isReq.incomeSourceId()));
            scenarioIncomeSourceRepository.save(
                    new ScenarioIncomeSourceEntity(scenario, incomeSource, isReq.overrideAnnualAmount()));
        }
    }

    private void addAccountsToScenario(ProjectionScenarioEntity scenario, UUID tenantId,
                                        List<CreateProjectionAccountRequest> accounts) {
        if (accounts == null) {
            return;
        }
        for (var acctReq : accounts) {
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

    private static void validateEndAge(Integer endAge) {
        if (endAge == null) {
            return;
        }
        if (endAge < MIN_END_AGE || endAge > MAX_END_AGE) {
            throw new IllegalArgumentException(
                    "end_age must be between " + MIN_END_AGE + " and " + MAX_END_AGE);
        }
    }

    private Map<String, Object> extractParamsFromRequest(ScenarioParamsSource r) {
        return scenarioParams(r.birthYear(), r.withdrawalRate(), r.withdrawalStrategy(),
                r.dynamicCeiling(), r.dynamicFloor(), r.filingStatus(),
                r.otherIncome(), r.annualRothConversion(), r.withdrawalOrder(),
                r.dynamicSequencingBracketRate(), r.rothConversionStrategy(),
                r.targetBracketRate(), r.rothConversionStartYear(), r.state(),
                r.primaryResidencePropertyTax(), r.primaryResidenceMortgageInterest());
    }

    private Map<String, Object> scenarioParams(
            Integer birthYear, BigDecimal withdrawalRate, String withdrawalStrategy,
            BigDecimal dynamicCeiling, BigDecimal dynamicFloor, String filingStatus,
            BigDecimal otherIncome, BigDecimal annualRothConversion, String withdrawalOrder,
            BigDecimal dynamicSequencingBracketRate, String rothConversionStrategy,
            BigDecimal targetBracketRate, Integer rothConversionStartYear,
            String state, BigDecimal primaryResidencePropertyTax,
            BigDecimal primaryResidenceMortgageInterest) {
        var params = new LinkedHashMap<String, Object>();
        params.put("birth_year", birthYear);
        params.put("withdrawal_rate", withdrawalRate);
        params.put("withdrawal_strategy", withdrawalStrategy);
        params.put("dynamic_ceiling", dynamicCeiling);
        params.put("dynamic_floor", dynamicFloor);
        params.put("filing_status", filingStatus);
        params.put("other_income", otherIncome);
        params.put("annual_roth_conversion", annualRothConversion);
        params.put("withdrawal_order", withdrawalOrder);
        params.put("dynamic_sequencing_bracket_rate", dynamicSequencingBracketRate);
        params.put("roth_conversion_strategy", rothConversionStrategy);
        params.put("target_bracket_rate", targetBracketRate);
        params.put("roth_conversion_start_year", rothConversionStartYear);
        params.put("state", state);
        params.put("primary_residence_property_tax", primaryResidencePropertyTax);
        params.put("primary_residence_mortgage_interest", primaryResidenceMortgageInterest);
        return params;
    }

    private String buildParamsJson(Map<String, Object> params) {
        ObjectNode node = objectMapper.createObjectNode();
        boolean hasContent = false;
        for (var entry : params.entrySet()) {
            hasContent |= putIfNotNull(node, entry.getKey(), entry.getValue());
        }
        return hasContent ? node.toString() : null;
    }

    private boolean putIfNotNull(ObjectNode node, String key, Object value) {
        switch (value) {
            case null -> {
                return false;
            }
            case Integer i -> node.put(key, i);
            case BigDecimal bd -> node.put(key, bd);
            case String s -> node.put(key, s);
            default -> { }
        }
        return true;
    }

    private BigDecimal computeRentalNetCashFlow(String incomeType, PropertyEntity property,
                                                 BigDecimal grossAnnual) {
        if (!"rental_property".equals(incomeType) || property == null) {
            return null;
        }
        BigDecimal expenses = sumNullable(property.getAnnualInsuranceCost(),
                property.getAnnualMaintenanceCost(),
                property.getAnnualPropertyTax());
        if (property.hasLoanDetails()) {
            var remaining = AmortizationCalculator.remainingBalance(
                    property.getLoanAmount(), property.getAnnualInterestRate(),
                    property.getLoanTermMonths(), property.getLoanStartDate(), LocalDate.now());
            if (remaining.compareTo(BigDecimal.ZERO) > 0) {
                var interest = remaining.multiply(property.getAnnualInterestRate())
                        .setScale(Money.SCALE, Money.ROUNDING);
                var annualPayment = AmortizationCalculator.monthlyPayment(
                        property.getLoanAmount(), property.getAnnualInterestRate(),
                        property.getLoanTermMonths()).multiply(new BigDecimal("12"));
                var principal = annualPayment.subtract(interest).max(BigDecimal.ZERO);
                var debtService = interest.add(principal);
                expenses = expenses == null ? debtService : expenses.add(debtService);
            }
        }
        return expenses != null
                ? grossAnnual.subtract(expenses).max(BigDecimal.ZERO)
                : grossAnnual;
    }

    private static BigDecimal sumNullable(BigDecimal... values) {
        BigDecimal total = null;
        for (var v : values) {
            if (v != null) {
                total = total == null ? v : total.add(v);
            }
        }
        return total;
    }
}
