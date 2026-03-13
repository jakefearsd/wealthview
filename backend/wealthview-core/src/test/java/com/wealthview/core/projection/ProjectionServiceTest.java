package com.wealthview.core.projection;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.ScenarioIncomeSourceInput;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.IncomeSourceEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.ScenarioIncomeSourceEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.IncomeSourceRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import com.wealthview.persistence.repository.ScenarioIncomeSourceRepository;
import com.wealthview.persistence.repository.SpendingProfileRepository;
import com.wealthview.persistence.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    @Mock
    private ProjectionScenarioRepository scenarioRepository;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private SpendingProfileRepository spendingProfileRepository;

    @Mock
    private ProjectionEngine projectionEngine;

    @Mock
    private AccountService accountService;

    @Mock
    private ScenarioIncomeSourceRepository scenarioIncomeSourceRepository;

    @Mock
    private IncomeSourceRepository incomeSourceRepository;

    @Mock
    private ProjectionInputBuilder projectionInputBuilder;

    @InjectMocks
    private ProjectionService service;

    private UUID tenantId;
    private UUID scenarioId;
    private TenantEntity tenant;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        scenarioId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
    }

    @Test
    void createScenario_validRequest_createsAndReturns() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateScenarioRequest(
                "Retirement Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null, null, null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        null)),
                null, null);

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
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateScenarioRequest(
                "Dynamic Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                "vanguard_dynamic_spending",
                new BigDecimal("0.05"),
                new BigDecimal("-0.025"),
                null, null, null, null, null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        null)),
                null, null);

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
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateScenarioRequest(
                "Multi-Pool Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null, null, null, null, null,
                List.of(
                        new CreateProjectionAccountRequest(null, new BigDecimal("200000"),
                                new BigDecimal("10000"), new BigDecimal("0.07"), "traditional"),
                        new CreateProjectionAccountRequest(null, new BigDecimal("100000"),
                                new BigDecimal("5000"), new BigDecimal("0.07"), "roth")),
                null, null);

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
    void createScenario_withWithdrawalOrder_persistsInParamsJson() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateScenarioRequest(
                "Traditional First Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                null, null, null,
                "traditional_first", null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        "traditional")),
                null, null);

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
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var request = new CreateScenarioRequest(
                "Fill Bracket Plan",
                LocalDate.of(2055, 1, 1),
                90,
                new BigDecimal("0.0300"),
                1990,
                new BigDecimal("0.04"),
                null, null, null,
                "single", null, null, null,
                "fill_bracket", new BigDecimal("0.12"), null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("200000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        "traditional")),
                null, null);

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
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));
        var linkedAccountId = UUID.randomUUID();
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");

        when(accountRepository.findByTenant_IdAndId(tenantId, linkedAccountId))
                .thenReturn(Optional.of(linkedAccount));
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(new BigDecimal("150000.00"));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateScenarioRequest(
                "Linked Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        linkedAccountId, new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("0.07"), "taxable")),
                null, null);

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
    void compareScenarios_validIds_runsAllAndReturnsResults() {
        var id1 = UUID.randomUUID();
        var id2 = UUID.randomUUID();
        var scenario1 = new ProjectionScenarioEntity(tenant, "Plan A",
                LocalDate.of(2055, 1, 1), 90, new BigDecimal("0.03"), null);
        var scenario2 = new ProjectionScenarioEntity(tenant, "Plan B",
                LocalDate.of(2060, 1, 1), 90, new BigDecimal("0.03"), null);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, id1))
                .thenReturn(Optional.of(scenario1));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, id2))
                .thenReturn(Optional.of(scenario2));

        var input1 = new ProjectionInput(id1, "Plan A", LocalDate.of(2055, 1, 1),
                90, new BigDecimal("0.03"), null, List.of(), null, null, List.of());
        var input2 = new ProjectionInput(id2, "Plan B", LocalDate.of(2060, 1, 1),
                90, new BigDecimal("0.03"), null, List.of(), null, null, List.of());
        when(projectionInputBuilder.build(scenario1, tenantId)).thenReturn(input1);
        when(projectionInputBuilder.build(scenario2, tenantId)).thenReturn(input2);

        var result1 = new ProjectionResultResponse(id1, List.of(), BigDecimal.ZERO, 0, null);
        var result2 = new ProjectionResultResponse(id2, List.of(), BigDecimal.ZERO, 0, null);
        when(projectionEngine.run(input1)).thenReturn(result1);
        when(projectionEngine.run(input2)).thenReturn(result2);

        var response = service.compareScenarios(tenantId, new CompareRequest(List.of(id1, id2)));

        assertThat(response.results()).hasSize(2);
    }

    @Test
    void compareScenarios_notFound_throwsEntityNotFoundException() {
        var id1 = UUID.randomUUID();
        when(scenarioRepository.findByTenant_IdAndId(tenantId, id1))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.compareScenarios(tenantId,
                new CompareRequest(List.of(id1, UUID.randomUUID()))))
                .isInstanceOf(EntityNotFoundException.class);
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

        var request = new UpdateScenarioRequest(
                "Updated Plan",
                LocalDate.of(2060, 6, 15),
                95,
                new BigDecimal("0.0250"),
                1985,
                new BigDecimal("0.035"),
                "dynamic_percentage",
                null, null, null, null, null, null, null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null, new BigDecimal("200000"),
                        new BigDecimal("15000"), new BigDecimal("0.08"), "traditional")),
                null, null);

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
    void updateScenario_notFound_throwsEntityNotFoundException() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        var request = new UpdateScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null, List.of(), null, null);

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

        var request = new UpdateScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(
                        new CreateProjectionAccountRequest(null, new BigDecimal("200000"),
                                new BigDecimal("10000"), new BigDecimal("0.07"), "traditional"),
                        new CreateProjectionAccountRequest(null, new BigDecimal("100000"),
                                new BigDecimal("5000"), new BigDecimal("0.07"), "roth")),
                null, null);

        var result = service.updateScenario(tenantId, scenarioId, request);

        assertThat(result.accounts()).hasSize(2);
    }

    @Test
    void runProjection_exists_delegatesToEngine() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var input = new ProjectionInput(scenarioId, "Plan", LocalDate.of(2055, 1, 1),
                90, new BigDecimal("0.03"), null, List.of(), null, null, List.of());
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(input);

        var engineResult = new ProjectionResultResponse(
                scenarioId,
                List.of(ProjectionYearDto.simple(2026, 36, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, false)),
                BigDecimal.ZERO, 0, null);
        when(projectionEngine.run(input)).thenReturn(engineResult);

        var result = service.runProjection(tenantId, scenarioId);

        assertThat(result).isEqualTo(engineResult);
        verify(projectionEngine).run(input);
    }

    @Test
    void runProjection_delegatesToInputBuilder() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var input = new ProjectionInput(scenarioId, "Plan", LocalDate.of(2055, 1, 1),
                90, new BigDecimal("0.03"), null, List.of(), null, null, List.of());
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(input);

        var engineResult = new ProjectionResultResponse(scenarioId, List.of(), BigDecimal.ZERO, 0, null);
        when(projectionEngine.run(input)).thenReturn(engineResult);

        service.runProjection(tenantId, scenarioId);

        verify(projectionInputBuilder).build(scenario, tenantId);
        verify(projectionEngine).run(input);
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

        var result = service.listScenarios(tenantId);

        assertThat(result.getFirst().accounts().getFirst().initialBalance())
                .isEqualByComparingTo(new BigDecimal("75000.00"));
    }

    @Test
    void createScenario_withIncomeSources_linksIncomeSources() {
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var incomeSourceId = UUID.randomUUID();
        var incomeSource = new IncomeSourceEntity(
                tenant, "Social Security", "social_security",
                new BigDecimal("24000"), 67, null,
                BigDecimal.ZERO, false, "taxable");

        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, incomeSourceId))
                .thenReturn(Optional.of(incomeSource));
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateScenarioRequest(
                "Plan with Income", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), 1990, new BigDecimal("0.04"),
                null, null, null, null, null, null, null, null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null, new BigDecimal("100000"),
                        new BigDecimal("10000"), new BigDecimal("0.07"), "taxable")),
                null,
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
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant));

        var badId = UUID.randomUUID();
        when(incomeSourceRepository.findByTenant_IdAndId(tenantId, badId))
                .thenReturn(Optional.empty());
        when(scenarioRepository.save(any(ProjectionScenarioEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new CreateScenarioRequest(
                "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), null,
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

        var request = new UpdateScenarioRequest(
                "Updated Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null, null, null, null, null,
                null, null, null, null, null, null, null,
                List.of(), null,
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
        when(scenarioIncomeSourceRepository.findByScenario_Id(scenario.getId()))
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
}
