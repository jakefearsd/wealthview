package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.common.Entities;
import com.wealthview.core.common.Money;
import com.wealthview.core.projection.dto.AllocationDto;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.GuardrailProfileSummary;
import com.wealthview.core.projection.dto.ProjectionAccountResponse;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceInput;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceResponse;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.core.projection.dto.ScenarioParamsSource;
import com.wealthview.core.projection.dto.ScenarioRequest;
import com.wealthview.core.projection.dto.ScenarioResponse;
import com.wealthview.core.projection.dto.SpendingProfileResponse;
import com.wealthview.core.property.PropertyFinance;
import com.wealthview.core.tenant.TenantLookup;
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
import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.ObjectMapper;

// GodClass: this service is the single orchestration point for scenario CRUD -- request
// validation, params_json assembly, account/income-source wiring, and response mapping are all
// tightly coupled to that one lifecycle (mirrors the identical, identically-justified suppression
// on GuardrailProfileService). Adding validateFeeRate (audit B1) pushed WMC just over PMD's
// default GodClass threshold; splitting it would scatter cohesive validation logic, not fix a
// design smell.
@SuppressWarnings("PMD.GodClass")
@Service
public class ScenarioCrudService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioCrudService.class);

    private static final int MIN_END_AGE = 50;
    private static final int MAX_END_AGE = 120;
    private static final BigDecimal MAX_DIVIDEND_YIELD = new BigDecimal("0.10");
    private static final BigDecimal MAX_FEE_RATE = new BigDecimal("0.03");
    private static final BigDecimal MAX_INTEREST_YIELD = new BigDecimal("0.10");
    private static final int MIN_DEATH_AGE = 50;
    private static final int MAX_DEATH_AGE = 120;
    private static final BigDecimal MIN_SURVIVOR_SPENDING_FACTOR = new BigDecimal("0.5");
    private static final BigDecimal MAX_SURVIVOR_SPENDING_FACTOR = BigDecimal.ONE;
    private static final Set<String> VALID_ACCOUNT_OWNERS = Set.of("primary", "spouse", "joint");

    private final ProjectionScenarioRepository scenarioRepository;
    private final TenantLookup tenantLookup;
    private final AccountRepository accountRepository;
    private final SpendingProfileRepository spendingProfileRepository;
    private final AccountService accountService;
    private final ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;
    private final IncomeSourceRepository incomeSourceRepository;
    private final GuardrailSpendingProfileRepository guardrailProfileRepository;
    private final SecurityClassificationService classificationService;
    private final MeterRegistry meterRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScenarioCrudService(ProjectionScenarioRepository scenarioRepository,
                               TenantLookup tenantLookup,
                               AccountRepository accountRepository,
                               SpendingProfileRepository spendingProfileRepository,
                               AccountService accountService,
                               ScenarioIncomeSourceRepository scenarioIncomeSourceRepository,
                               IncomeSourceRepository incomeSourceRepository,
                               GuardrailSpendingProfileRepository guardrailProfileRepository,
                               SecurityClassificationService classificationService,
                               MeterRegistry meterRegistry) {
        this.scenarioRepository = scenarioRepository;
        this.tenantLookup = tenantLookup;
        this.accountRepository = accountRepository;
        this.spendingProfileRepository = spendingProfileRepository;
        this.accountService = accountService;
        this.scenarioIncomeSourceRepository = scenarioIncomeSourceRepository;
        this.incomeSourceRepository = incomeSourceRepository;
        this.guardrailProfileRepository = guardrailProfileRepository;
        this.classificationService = classificationService;
        this.meterRegistry = meterRegistry;
    }

    @Transactional
    public ScenarioResponse createScenario(UUID tenantId, ScenarioRequest request) {
        validateEndAge(request.endAge());
        validateDividendYield(request.dividendYield());
        validateFeeRate(request.feeRate());
        validateInterestYield(request.interestYield());
        validateHouseholdFields(request);
        validateAccounts(request.accounts());
        var tenant = tenantLookup.requireTenant(tenantId);

        String paramsJson = ScenarioParams.from(request).toJson(objectMapper);

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
    public ScenarioResponse updateScenario(UUID tenantId, UUID scenarioId, ScenarioRequest request) {
        validateEndAge(request.endAge());
        validateDividendYield(request.dividendYield());
        validateFeeRate(request.feeRate());
        validateInterestYield(request.interestYield());
        validateHouseholdFields(request);
        validateAccounts(request.accounts());
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Scenario"));

        scenario.setName(request.name());
        scenario.setRetirementDate(request.retirementDate());
        scenario.setEndAge(request.endAge());
        scenario.setInflationRate(request.inflationRate());
        scenario.setParamsJson(ScenarioParams.from(request).toJson(objectMapper));

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
            var newHash = GuardrailProfileService.computeScenarioHash(
                    saved, currentIncomeSourceSignatures(scenarioId));
            if (!newHash.equals(profile.getScenarioHash())) {
                profile.setStale(true);
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
                    var linked = acct.getLinkedAccount();
                    var balance = linked != null
                            ? accountService.computeBalance(linked, tenantId)
                            : acct.getInitialBalance();
                    var allocation = effectiveAllocation(acct, tenantId);
                    var allocationIsOverride = acct.getAllocation() != null;
                    var costBasis = linked != null
                            ? accountService.computeCostBasis(linked, tenantId)
                            : acct.getCostBasis() != null ? acct.getCostBasis() : acct.getInitialBalance();
                    return ProjectionAccountResponse.from(acct, balance, allocation, allocationIsOverride, costBasis);
                })
                .toList();
    }

    private AllocationDto effectiveAllocation(ProjectionAccountEntity acct, UUID tenantId) {
        if (acct.getAllocation() != null) {
            var weights = new EnumMap<AssetClass, BigDecimal>(AssetClass.class);
            acct.getAllocation().forEach((k, v) -> weights.put(AssetClass.fromKey(k), v));
            return AllocationDto.fromAllocation(new AssetAllocation(weights));
        }
        if (acct.getLinkedAccount() != null) {
            return AllocationDto.fromAllocation(
                    classificationService.deriveAllocation(tenantId, acct.getLinkedAccount().getId()).allocation());
        }
        return AllocationDto.fromAllocation(AssetAllocation.ALL_US);
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

    /**
     * Builds the (id, effective-amount, owner, survivor-percent) income-source signatures the
     * guardrail staleness check hashes alongside the scenario (see
     * {@link GuardrailProfileService#computeScenarioHash}), so an income-source add, amount edit,
     * or owner/survivor-percent edit is detected the same way an account edit already is.
     */
    private List<GuardrailProfileService.IncomeSourceSignature> currentIncomeSourceSignatures(UUID scenarioId) {
        return scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenarioId).stream()
                .map(link -> {
                    var src = link.getIncomeSource();
                    var effective = link.getOverrideAnnualAmount() != null
                            ? link.getOverrideAnnualAmount() : src.getAnnualAmount();
                    return new GuardrailProfileService.IncomeSourceSignature(
                            src.getId(), effective, src.getOwner(), src.getSurvivorPercent());
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
            // Linked accounts always derive cost basis live from holdings (ProjectionInputBuilder);
            // the stored field is only meaningful for hypothetical accounts.
            projAcct.setCostBasis(linkedAccount != null ? null : acctReq.costBasis());
            projAcct.setOwner(acctReq.owner() != null ? acctReq.owner() : "primary");
            if (acctReq.allocation() != null) {
                acctReq.allocation().validate();
                projAcct.setAllocation(acctReq.allocation().toWeightMap());
            }
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

    private static void validateDividendYield(BigDecimal dividendYield) {
        if (dividendYield == null) {
            return;
        }
        if (dividendYield.signum() < 0 || dividendYield.compareTo(MAX_DIVIDEND_YIELD) > 0) {
            throw new IllegalArgumentException("dividend_yield must be between 0 and 0.10");
        }
    }

    private static void validateFeeRate(BigDecimal feeRate) {
        if (feeRate == null) {
            return;
        }
        if (feeRate.signum() < 0 || feeRate.compareTo(MAX_FEE_RATE) > 0) {
            throw new IllegalArgumentException("fee_rate must be between 0 and 0.03");
        }
    }

    private static void validateInterestYield(BigDecimal interestYield) {
        if (interestYield == null) {
            return;
        }
        if (interestYield.signum() < 0 || interestYield.compareTo(MAX_INTEREST_YIELD) > 0) {
            throw new IllegalArgumentException("interest_yield must be between 0 and 0.10");
        }
    }

    /**
     * Household/survivor modeling (sub-project A): death ages (when set) must be plausible
     * planning ages; every spouse-scoped or household-only field requires {@code spouse_birth_year}
     * to be set (a household field with no spouse is meaningless and almost certainly a client
     * bug); the survivor spending factor (when set) must be in its documented 0.5-1.0 range.
     * {@code primary_death_age} is exempt from the spouse-presence check but is currently INERT
     * for single-person scenarios: {@code ProjectionInputBuilder.resolveHousehold} builds the
     * degenerate {@code HouseholdContext.single(...)} (empty transition/second-death optionals),
     * so no truncation occurs without a spouse — deliberately preserving pre-household behavior
     * for singles. Range-validated anyway so a stored value is sane if a future change wires it.
     */
    private static void validateHouseholdFields(ScenarioParamsSource request) {
        validateDeathAge(request.primaryDeathAge(), "primary_death_age");
        validateDeathAge(request.spouseDeathAge(), "spouse_death_age");
        validateSurvivorSpendingFactor(request.survivorSpendingFactor());
        if (request.spouseBirthYear() == null && (request.spouseDeathAge() != null
                || request.survivorSpendingFactor() != null || request.communityProperty() != null)) {
            throw new IllegalArgumentException(
                    "spouse_death_age, survivor_spending_factor, and community_property require "
                            + "spouse_birth_year to be set");
        }
    }

    private static void validateDeathAge(Integer deathAge, String fieldName) {
        if (deathAge == null) {
            return;
        }
        if (deathAge < MIN_DEATH_AGE || deathAge > MAX_DEATH_AGE) {
            throw new IllegalArgumentException(fieldName + " must be between " + MIN_DEATH_AGE
                    + " and " + MAX_DEATH_AGE);
        }
    }

    private static void validateSurvivorSpendingFactor(BigDecimal survivorSpendingFactor) {
        if (survivorSpendingFactor == null) {
            return;
        }
        if (survivorSpendingFactor.compareTo(MIN_SURVIVOR_SPENDING_FACTOR) < 0
                || survivorSpendingFactor.compareTo(MAX_SURVIVOR_SPENDING_FACTOR) > 0) {
            throw new IllegalArgumentException("survivor_spending_factor must be between 0.5 and 1.0");
        }
    }

    private static void validateAccounts(List<CreateProjectionAccountRequest> accounts) {
        if (accounts == null) {
            return;
        }
        for (var account : accounts) {
            validateAccountOwner(account.owner(), account.accountType());
        }
    }

    private static void validateAccountOwner(String owner, String accountType) {
        if (owner == null) {
            return;
        }
        if (!VALID_ACCOUNT_OWNERS.contains(owner)) {
            throw new IllegalArgumentException("owner must be one of: " + VALID_ACCOUNT_OWNERS);
        }
        var resolvedType = accountType != null ? accountType : "taxable";
        if ("joint".equals(owner) && !"taxable".equals(resolvedType)) {
            throw new IllegalArgumentException("owner 'joint' is only valid for taxable accounts");
        }
    }

    private BigDecimal computeRentalNetCashFlow(String incomeType, PropertyEntity property,
                                                 BigDecimal grossAnnual) {
        if (!"rental_property".equals(incomeType) || property == null) {
            return null;
        }
        var expenses = Money.sum(property.getAnnualInsuranceCost(),
                property.getAnnualMaintenanceCost(),
                property.getAnnualPropertyTax())
                .add(PropertyFinance.annualDebtService(property, LocalDate.now())
                        .map(PropertyFinance.AnnualDebtService::total)
                        .orElse(BigDecimal.ZERO));
        return grossAnnual.subtract(expenses).max(BigDecimal.ZERO);
    }
}
