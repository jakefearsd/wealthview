package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.AllocationDto;
import com.wealthview.core.projection.dto.AssetAllocation;
import com.wealthview.core.projection.dto.AssetClass;
import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.ScenarioRequest;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceInput;
import com.wealthview.core.tenant.TenantLookup;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.PropertyEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.entity.SpendingProfileEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioCrudServiceTest {

    @Mock
    private ProjectionScenarioRepository scenarioRepository;

    @Mock
    private TenantLookup tenantLookup;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private SpendingProfileRepository spendingProfileRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;

    @Mock
    private IncomeSourceRepository incomeSourceRepository;

    @Mock
    private GuardrailSpendingProfileRepository guardrailProfileRepository;

    @Mock
    private SecurityClassificationService classificationService;

    private ScenarioCrudService service;
    private SimpleMeterRegistry meterRegistry;

    private UUID tenantId;
    private UUID scenarioId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        scenarioId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        meterRegistry = new SimpleMeterRegistry();
        service = new ScenarioCrudService(scenarioRepository, tenantLookup, accountRepository,
                spendingProfileRepository, accountService, scenarioIncomeSourceRepository,
                incomeSourceRepository, guardrailProfileRepository, classificationService, meterRegistry);
    }

    private ScenarioRequest scenarioRequestWithAccounts(List<CreateProjectionAccountRequest> accounts) {
        return new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                accounts, null, null, null);
    }

    private ProjectionScenarioEntity captureSavedScenario() {
        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void createScenario_validRequest_createsAndReturns() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var request = new ScenarioRequest(
                "Retirement Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        null)),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.createScenario(tenantId, request);

        assertThat(result.name()).isEqualTo("Retirement Plan");
        assertThat(result.retirementDate()).isEqualTo(LocalDate.of(2055, 1, 1));
        assertThat(result.endAge()).isEqualTo(90);
        assertThat(result.accounts()).hasSize(1);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getParamsJson()).contains("\"birth_year\":1990");
        assertThat(saved.getParamsJson()).contains("\"withdrawal_rate\":0.04");
    }

    @Test
    void createScenario_withDynamicStrategy_persistsInParamsJson() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var request = new ScenarioRequest(
                "Dynamic Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                "vanguard_dynamic_spending",
                new BigDecimal("0.05"),
                new BigDecimal("-0.025"),
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        null)),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getParamsJson()).contains("\"withdrawal_strategy\":\"vanguard_dynamic_spending\"");
        assertThat(saved.getParamsJson()).contains("\"dynamic_ceiling\"");
        assertThat(saved.getParamsJson()).contains("\"dynamic_floor\"");
    }

    @Test
    void createScenario_withAccountTypes_persistsTypes() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var request = new ScenarioRequest(
                "Multi-Pool Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(
                        new CreateProjectionAccountRequest(null, new BigDecimal("200000"),
                                new BigDecimal("10000"), new BigDecimal("0.07"), "traditional"),
                        new CreateProjectionAccountRequest(null, new BigDecimal("100000"),
                                new BigDecimal("5000"), new BigDecimal("0.07"), "roth")),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.createScenario(tenantId, request);

        assertThat(result.accounts()).hasSize(2);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getAccounts().get(0).getAccountType()).isEqualTo("traditional");
        assertThat(saved.getAccounts().get(1).getAccountType()).isEqualTo("roth");
    }

    @Test
    void createScenario_hypotheticalAccountWithCostBasis_persistsCostBasis() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var request = new ScenarioRequest(
                "Taxable Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null, new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("0.07"), new BigDecimal("62000"), "taxable")),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getAccounts().get(0).getCostBasis()).isEqualByComparingTo("62000");
    }

    @Test
    void createScenario_linkedAccountWithCostBasis_ignoresRequestCostBasis() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        var linkedAccountId = UUID.randomUUID();
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        when(accountRepository.findByTenant_IdAndId(tenantId, linkedAccountId))
                .thenReturn(Optional.of(linkedAccount));
        when(classificationService.deriveAllocation(tenantId, linkedAccount.getId()))
                .thenReturn(new SecurityClassificationService.AllocationResult(AssetAllocation.ALL_US, Set.of()));

        var request = new ScenarioRequest(
                "Linked Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        linkedAccountId, new BigDecimal("100000"), new BigDecimal("10000"),
                        new BigDecimal("0.07"), new BigDecimal("62000"), "taxable")),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        // Linked accounts derive cost basis live from holdings; the entity field stays null
        // regardless of what the request carried.
        assertThat(saved.getAccounts().get(0).getCostBasis()).isNull();
    }

    @Test
    void createScenario_manualAccountWithAllocation_persistsWeightMap() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var alloc = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("5"));
        var request = scenarioRequestWithAccounts(List.of(new CreateProjectionAccountRequest(
                null, new BigDecimal("100000"), new BigDecimal("0"), null, new BigDecimal("90000"),
                alloc, "taxable")));

        service.createScenario(tenantId, request);

        var saved = captureSavedScenario();
        assertThat(saved.getAccounts().get(0).getAllocation())
                .containsEntry("us_stock", new BigDecimal("60"))
                .containsEntry("cash", new BigDecimal("5"));
    }

    @Test
    void createScenario_allocationSumNot100_throws() {
        var bad = new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("10"));
        var request = scenarioRequestWithAccounts(List.of(new CreateProjectionAccountRequest(
                null, new BigDecimal("100000"), new BigDecimal("0"), null, null, bad, "taxable")));

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("100");
    }

    @Test
    void getScenario_manualAccountWithAllocationOverride_responseExposesOverridePercentages() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var acct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("0"), null, "taxable");
        acct.setAllocation(new AllocationDto(new BigDecimal("60"), new BigDecimal("20"),
                new BigDecimal("15"), new BigDecimal("5")).toWeightMap());
        scenario.addAccount(acct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var result = service.getScenario(tenantId, scenarioId);

        var allocation = result.accounts().getFirst().allocation();
        assertThat(allocation.usStock()).isEqualByComparingTo("60");
        assertThat(allocation.intlStock()).isEqualByComparingTo("20");
        assertThat(allocation.bond()).isEqualByComparingTo("15");
        assertThat(allocation.cash()).isEqualByComparingTo("5");
    }

    @Test
    void getScenario_manualAccountNoOverride_responseAllocationIsAllUsStock() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var acct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("0"), null, "taxable");
        scenario.addAccount(acct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var result = service.getScenario(tenantId, scenarioId);

        var allocation = result.accounts().getFirst().allocation();
        assertThat(allocation.usStock()).isEqualByComparingTo("100");
        assertThat(allocation.intlStock()).isEqualByComparingTo("0");
        assertThat(allocation.bond()).isEqualByComparingTo("0");
        assertThat(allocation.cash()).isEqualByComparingTo("0");
    }

    @Test
    void getScenario_linkedAccountNoOverride_responseAllocationFromDerivedMix() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var acct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("0"), null, "taxable");
        scenario.addAccount(acct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("150000"));
        when(accountService.computeCostBasis(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("120000"));
        when(classificationService.deriveAllocation(tenantId, linkedAccount.getId()))
                .thenReturn(new SecurityClassificationService.AllocationResult(
                        AssetAllocation.fromDoubles(java.util.Map.of(
                                AssetClass.US_STOCK, 0.5, AssetClass.BOND, 0.5)), Set.of()));

        var result = service.getScenario(tenantId, scenarioId);

        var allocation = result.accounts().getFirst().allocation();
        assertThat(allocation.usStock()).isEqualByComparingTo("50");
        assertThat(allocation.bond()).isEqualByComparingTo("50");
        assertThat(allocation.intlStock()).isEqualByComparingTo("0");
    }

    @Test
    void getScenario_linkedAccount_responseCostBasisFromComputeCostBasis() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var acct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("0"), null, "taxable");
        scenario.addAccount(acct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("150000"));
        when(accountService.computeCostBasis(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("120000"));
        when(classificationService.deriveAllocation(tenantId, linkedAccount.getId()))
                .thenReturn(new SecurityClassificationService.AllocationResult(AssetAllocation.ALL_US, Set.of()));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.accounts().getFirst().costBasis()).isEqualByComparingTo("120000");
    }

    @Test
    void getScenario_manualAccountWithStoredCostBasis_responseUsesStoredCostBasis() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var acct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("0"), null, "taxable");
        acct.setCostBasis(new BigDecimal("62000"));
        scenario.addAccount(acct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.accounts().getFirst().costBasis()).isEqualByComparingTo("62000");
    }

    @Test
    void getScenario_manualAccountNullCostBasis_responseFallsBackToInitialBalance() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var acct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("0"), null, "taxable");
        scenario.addAccount(acct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.accounts().getFirst().costBasis()).isEqualByComparingTo("100000");
    }

    @Test
    void createScenario_withWithdrawalOrder_persistsInParamsJson() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var request = new ScenarioRequest(
                "Traditional First Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null,
                "traditional_first", null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        "traditional")),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getParamsJson()).contains("\"withdrawal_order\":\"traditional_first\"");
    }

    @Test
    void createScenario_withFillBracketStrategy_persistsInParamsJson() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var request = new ScenarioRequest(
                "Fill Bracket Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                "single", null, null, null,
                null, "fill_bracket", new BigDecimal("0.12"), null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("200000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        "traditional")),
                null, null, null);

        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getParamsJson()).contains("\"roth_conversion_strategy\":\"fill_bracket\"");
        assertThat(saved.getParamsJson()).contains("\"target_bracket_rate\":0.12");
    }

    @Test
    void createScenario_withLinkedAccount_storesNullBalance() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);
        var linkedAccountId = UUID.randomUUID();
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");

        when(accountRepository.findByTenant_IdAndId(tenantId, linkedAccountId))
                .thenReturn(Optional.of(linkedAccount));
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("150000.00"));
        when(classificationService.deriveAllocation(tenantId, linkedAccount.getId()))
                .thenReturn(new SecurityClassificationService.AllocationResult(AssetAllocation.ALL_US, Set.of()));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ScenarioRequest(
                "Linked Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        linkedAccountId, new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("0.07"), "taxable")),
                null, null, null);

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getAccounts().getFirst().getInitialBalance()).isNull();
    }

    @Test
    void getScenario_exists_returnsScenario() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.name()).isEqualTo("Plan");
    }

    @Test
    void getScenario_notFound_throwsEntityNotFoundException() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getScenario(tenantId, scenarioId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void getScenario_withHypotheticalAccount_usesStoredBalance() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("10000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.accounts().getFirst().initialBalance())
                .isEqualByComparingTo(new BigDecimal("100000"));
        verifyNoInteractions(accountService);
    }

    @Test
    void listScenarios_returnsList() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(scenario));

        var result = service.listScenarios(tenantId);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().name()).isEqualTo("Plan");
    }

    @Test
    void deleteScenario_exists_deletes() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        service.deleteScenario(tenantId, scenarioId);

        verify(scenarioRepository).delete(scenario);
    }

    @Test
    void updateScenario_validRequest_updatesFieldsAndReturns() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Old Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var oldAccount = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("50000"),
                new BigDecimal("5000"), new BigDecimal("0.06"), "taxable");
        scenario.addAccount(oldAccount);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ScenarioRequest(
                "Updated Plan",
                LocalDate.of(2060, 6, 15),
                95,
                new BigDecimal("0.0250"),
                1985,
                new BigDecimal("0.035"),
                "dynamic_percentage",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null, new BigDecimal("200000"),
                        new BigDecimal("15000"), new BigDecimal("0.08"), "traditional")),
                null, null, null);

        var result = service.updateScenario(tenantId, scenarioId, request);

        assertThat(result.name()).isEqualTo("Updated Plan");
        assertThat(result.retirementDate()).isEqualTo(LocalDate.of(2060, 6, 15));
        assertThat(result.endAge()).isEqualTo(95);
        assertThat(result.inflationRate()).isEqualByComparingTo(new BigDecimal("0.0250"));
        assertThat(result.accounts()).hasSize(1);

        var captor = ArgumentCaptor.forClass(ProjectionScenarioEntity.class);
        verify(scenarioRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getParamsJson()).contains("\"birth_year\":1985");
        assertThat(saved.getParamsJson()).contains("\"withdrawal_strategy\":\"dynamic_percentage\"");
    }

    @Test
    void createScenario_endAgeOver120_throwsIllegalArgument() {
        var request = new ScenarioRequest(
                "Bad Plan",
                LocalDate.of(2055, 1, 1),
                150,
                new BigDecimal("0.03"),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end_age");
    }

    @Test
    void createScenario_endAgeBelow50_throwsIllegalArgument() {
        var request = new ScenarioRequest(
                "Bad Plan",
                LocalDate.of(2055, 1, 1),
                30,
                new BigDecimal("0.03"),
                null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end_age");
    }

    @Test
    void updateScenario_endAgeOver120_throwsIllegalArgument() {
        var request = new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 200,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.updateScenario(tenantId, scenarioId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("end_age");
    }

    @Test
    void updateScenario_notFound_throwsEntityNotFoundException() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        var request = new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, List.of(), null, null, null);

        assertThatThrownBy(() -> service.updateScenario(tenantId, scenarioId, request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void updateScenario_replacesAccounts() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var oldAccount = new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("10000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(oldAccount);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(
                        new CreateProjectionAccountRequest(null, new BigDecimal("200000"),
                                new BigDecimal("10000"), new BigDecimal("0.07"), "traditional"),
                        new CreateProjectionAccountRequest(null, new BigDecimal("100000"),
                                new BigDecimal("5000"), new BigDecimal("0.07"), "roth")),
                null, null, null);

        var result = service.updateScenario(tenantId, scenarioId, request);

        assertThat(result.accounts()).hasSize(2);
    }

    @Test
    void getScenario_withLinkedAccount_resolvesCurrentBalance() {
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");

        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("10000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("250000.00"));
        when(classificationService.deriveAllocation(tenantId, linkedAccount.getId()))
                .thenReturn(new SecurityClassificationService.AllocationResult(AssetAllocation.ALL_US, Set.of()));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.accounts().getFirst().initialBalance())
                .isEqualByComparingTo(new BigDecimal("250000.00"));
    }

    @Test
    void listScenarios_withLinkedAccount_resolvesCurrentBalance() {
        var linkedAccount = new AccountEntity(tenant, "401k", "401k", "Fidelity");

        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, null,
                new BigDecimal("5000"), new BigDecimal("0.07"), "traditional");
        scenario.addAccount(projAcct);

        when(scenarioRepository.findByTenant_IdOrderByCreatedAtDesc(tenantId))
                .thenReturn(List.of(scenario));

        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("75000.00"));
        when(classificationService.deriveAllocation(tenantId, linkedAccount.getId()))
                .thenReturn(new SecurityClassificationService.AllocationResult(AssetAllocation.ALL_US, Set.of()));

        var result = service.listScenarios(tenantId);

        assertThat(result.getFirst().accounts().getFirst().initialBalance())
                .isEqualByComparingTo(new BigDecimal("75000.00"));
    }

    @Test
    void createScenario_withIncomeSources_linksIncomeSources() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var incomeSourceId = UUID.randomUUID();
        var incomeSource = new IncomeSourceEntity(
                tenant, "Social Security", "social_security",
                new BigDecimal("24000"), 67, null,
                BigDecimal.ZERO, false, "taxable");

        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSourceId))
                .thenReturn(Optional.of(incomeSource));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ScenarioRequest(
                "Plan with Income", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), 1990, new BigDecimal("0.04"),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null, new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("0.07"), "taxable")),
                null, null,
                List.of(new ScenarioIncomeSourceInput(incomeSourceId, new BigDecimal("30000"))));

        service.createScenario(tenantId, request);

        var captor = ArgumentCaptor.forClass(ScenarioIncomeSourceEntity.class);
        verify(scenarioIncomeSourceRepository).save(captor.capture());
        var saved = captor.getValue();
        assertThat(saved.getIncomeSource()).isEqualTo(incomeSource);
        assertThat(saved.getOverrideAnnualAmount()).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void createScenario_withInvalidIncomeSourceId_throwsEntityNotFound() {
        when(tenantLookup.requireTenant(tenantId)).thenReturn(tenant);

        var badId = UUID.randomUUID();
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, badId))
                .thenReturn(Optional.empty());
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(), null, null,
                List.of(new ScenarioIncomeSourceInput(badId, null)));

        assertThatThrownBy(() -> service.createScenario(tenantId, request))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining(badId.toString());
    }

    @Test
    void updateScenario_replacesIncomeSources() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var incomeSourceId = UUID.randomUUID();
        var incomeSource = new IncomeSourceEntity(
                tenant, "Pension", "pension",
                new BigDecimal("36000"), 65, null,
                BigDecimal.ZERO, false, "taxable");

        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSourceId))
                .thenReturn(Optional.of(incomeSource));

        var request = new ScenarioRequest(
                "Updated Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(), null, null,
                List.of(new ScenarioIncomeSourceInput(incomeSourceId, null)));

        service.updateScenario(tenantId, scenarioId, request);

        verify(scenarioIncomeSourceRepository).deleteByScenario_Id(scenarioId);
        var captor = ArgumentCaptor.forClass(ScenarioIncomeSourceEntity.class);
        verify(scenarioIncomeSourceRepository).save(captor.capture());
        assertThat(captor.getValue().getIncomeSource()).isEqualTo(incomeSource);
        assertThat(captor.getValue().getOverrideAnnualAmount()).isNull();
    }

    @Test
    void toScenarioResponse_includesIncomeSources() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Social Security", "social_security",
                new BigDecimal("24000"), 67, null,
                BigDecimal.ZERO, false, "taxable");
        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, new BigDecimal("28000"));

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenario.getId()))
                .thenReturn(List.of(link));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.incomeSources()).hasSize(1);
        var isResp = result.incomeSources().getFirst();
        assertThat(isResp.name()).isEqualTo("Social Security");
        assertThat(isResp.incomeType()).isEqualTo("social_security");
        assertThat(isResp.annualAmount()).isEqualByComparingTo(new BigDecimal("24000"));
        assertThat(isResp.overrideAnnualAmount()).isEqualByComparingTo(new BigDecimal("28000"));
        assertThat(isResp.effectiveAmount()).isEqualByComparingTo(new BigDecimal("28000"));
    }

    // ── Guardrail Staleness Tests ──

    @Test
    void updateScenario_withGuardrailProfile_marksStaleWhenHashChanges() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Old Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1990}");
        var guardrailProfile = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Guardrail", new BigDecimal("30000"));
        guardrailProfile.setScenarioHash(GuardrailProfileService.computeScenarioHash(scenario));
        guardrailProfile.setStale(false);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(guardrailProfileRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.of(guardrailProfile));

        // Change endAge to trigger hash change
        var request = new ScenarioRequest(
                "Old Plan", LocalDate.of(2055, 1, 1), 95,
                new BigDecimal("0.03"), 1990, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(), null, null, null);

        service.updateScenario(tenantId, scenarioId, request);

        assertThat(guardrailProfile.isStale()).isTrue();
        verify(guardrailProfileRepository).save(guardrailProfile);
    }

    @Test
    void updateScenario_withGuardrailProfile_doesNotMarkStaleWhenHashUnchanged() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1990}");
        var guardrailProfile = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Guardrail", new BigDecimal("30000"));

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> {
                    var saved = (ProjectionScenarioEntity) inv.getArgument(0);
                    guardrailProfile.setScenarioHash(
                            GuardrailProfileService.computeScenarioHash(saved));
                    return saved;
                });
        when(guardrailProfileRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.of(guardrailProfile));

        // Same fields — no real change
        var request = new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), 1990, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(), null, null, null);

        service.updateScenario(tenantId, scenarioId, request);

        assertThat(guardrailProfile.isStale()).isFalse();
        verify(guardrailProfileRepository, never()).save(any(GuardrailSpendingProfileEntity.class));
    }

    @Test
    void updateScenario_withGuardrailProfile_irrelevantParamsDoNotTriggerStaleness() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1990}");
        var guardrailProfile = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Guardrail", new BigDecimal("30000"));

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> {
                    var saved = (ProjectionScenarioEntity) inv.getArgument(0);
                    guardrailProfile.setScenarioHash(
                            GuardrailProfileService.computeScenarioHash(saved));
                    return saved;
                });
        when(guardrailProfileRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.of(guardrailProfile));

        // Add filing_status and withdrawal_strategy — these should NOT affect guardrail hash
        var request = new ScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), 1990, null,
                "vanguard_dynamic_spending", null, null,
                "married_filing_jointly", null, null, null, null, null, null, null,
                null, null, null,
                List.of(), null, null, null);

        service.updateScenario(tenantId, scenarioId, request);

        assertThat(guardrailProfile.isStale()).isFalse();
        verify(guardrailProfileRepository, never()).save(any(GuardrailSpendingProfileEntity.class));
    }

    @Test
    void getScenario_withGuardrailProfileButSimpleSpendingActive_includesInactiveGuardrail() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        // Scenario has a spending profile set (guardrail FK is null on the scenario)
        var spendingProfile = new SpendingProfileEntity(
                tenant, "Basic Plan", new BigDecimal("40000"), new BigDecimal("20000"), null);
        scenario.setSpendingProfile(spendingProfile);

        // But a guardrail profile exists in the DB for this scenario
        var guardrailEntity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Optimized", new BigDecimal("30000"));

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(guardrailProfileRepository.findByScenario_Id(scenario.getId()))
                .thenReturn(Optional.of(guardrailEntity));

        var result = service.getScenario(tenantId, scenarioId);

        // Guardrail should be present in response but marked inactive
        assertThat(result.guardrailProfile()).isNotNull();
        assertThat(result.guardrailProfile().name()).isEqualTo("Optimized");
        assertThat(result.guardrailProfile().active()).isFalse();

        // Spending profile should also be present
        assertThat(result.spendingProfile()).isNotNull();
        assertThat(result.spendingProfile().name()).isEqualTo("Basic Plan");
    }

    @Test
    void updateScenario_noGuardrailProfile_doesNotInteractWithGuardrailRepo() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(guardrailProfileRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.empty());

        var request = new ScenarioRequest(
                "Updated", LocalDate.of(2060, 1, 1), 95,
                new BigDecimal("0.02"), null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null,
                List.of(), null, null, null);

        service.updateScenario(tenantId, scenarioId, request);

        verify(guardrailProfileRepository, never()).save(any(GuardrailSpendingProfileEntity.class));
    }

    @Test
    void getScenario_withRentalPropertyIncomeSource_populatesNetCashFlow() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var property = new PropertyEntity(
                tenant, "123 Main St", new BigDecimal("400000"),
                LocalDate.of(2020, 1, 1), new BigDecimal("450000"), BigDecimal.ZERO);
        property.setAnnualInsuranceCost(new BigDecimal("2500"));
        property.setAnnualMaintenanceCost(new BigDecimal("3000"));
        property.setAnnualPropertyTax(new BigDecimal("14000"));
        property.setLoanAmount(new BigDecimal("300000"));
        property.setAnnualInterestRate(new BigDecimal("0.065"));
        property.setLoanTermMonths(360);
        property.setLoanStartDate(LocalDate.of(2020, 1, 1));

        var incomeSource = new IncomeSourceEntity(
                tenant, "AirBnB", "rental_property",
                new BigDecimal("96000"), 54, null,
                BigDecimal.ZERO, false, "taxable");
        incomeSource.setProperty(property);

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenario.getId()))
                .thenReturn(List.of(link));

        var result = service.getScenario(tenantId, scenarioId);

        var isResp = result.incomeSources().getFirst();
        assertThat(isResp.annualNetCashFlow()).isNotNull();
        assertThat(isResp.annualNetCashFlow()).isGreaterThan(BigDecimal.ZERO);
        assertThat(isResp.annualNetCashFlow()).isLessThan(isResp.annualAmount());
    }

    @Test
    void getScenario_withNonRentalIncomeSource_netCashFlowIsNull() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Pension", "pension",
                new BigDecimal("36000"), 65, null,
                BigDecimal.ZERO, false, "taxable");

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenario.getId()))
                .thenReturn(List.of(link));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.incomeSources().getFirst().annualNetCashFlow()).isNull();
    }

    @Test
    void getScenario_withLinkedIncomeSource_returnsIncomeSourceData() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Pension", "pension",
                new BigDecimal("36000"), 65, null,
                BigDecimal.ZERO, false, "taxable");
        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        // Only the eager-fetch finder is stubbed: the income-source data below can
        // only appear in the response if the service reads through that fetch.
        when(scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenario.getId()))
                .thenReturn(List.of(link));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.incomeSources()).hasSize(1);
        var isResp = result.incomeSources().getFirst();
        assertThat(isResp.name()).isEqualTo("Pension");
        assertThat(isResp.incomeType()).isEqualTo("pension");
        assertThat(isResp.annualAmount()).isEqualByComparingTo(new BigDecimal("36000"));
        assertThat(isResp.effectiveAmount()).isEqualByComparingTo(new BigDecimal("36000"));
        assertThat(isResp.startAge()).isEqualTo(65);
    }

    @Test
    void getScenario_withRentalPropertyNoLinkedProperty_netCashFlowIsNull() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);

        var incomeSource = new IncomeSourceEntity(
                tenant, "Hypothetical Rental", "rental_property",
                new BigDecimal("24000"), 60, null,
                BigDecimal.ZERO, false, "taxable");
        // no property linked

        var link = new ScenarioIncomeSourceEntity(scenario, incomeSource, null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(scenarioIncomeSourceRepository.findWithIncomeSourceByScenarioId(scenario.getId()))
                .thenReturn(List.of(link));

        var result = service.getScenario(tenantId, scenarioId);

        assertThat(result.incomeSources().getFirst().annualNetCashFlow()).isNull();
    }

    @Test
    void deleteScenario_incrementsScenariosCounterWithDeleteAction() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90, new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        service.deleteScenario(tenantId, scenarioId);

        var counter = meterRegistry.find("wealthview.scenarios").tag("action", "delete").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }
}
