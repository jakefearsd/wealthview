package com.wealthview.core.projection;

import com.wealthview.core.account.AccountService;
import com.wealthview.core.account.dto.AccountResponse;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.core.projection.dto.UpdateScenarioRequest;
import com.wealthview.persistence.entity.AccountEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
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
import static org.mockito.Mockito.verify;
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
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        null)),
                null);

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
                null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"),
                        null)),
                null);

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
                null, null, null,
                List.of(
                        new CreateProjectionAccountRequest(null, new BigDecimal("200000"),
                                new BigDecimal("10000"), new BigDecimal("0.07"), "traditional"),
                        new CreateProjectionAccountRequest(null, new BigDecimal("100000"),
                                new BigDecimal("5000"), new BigDecimal("0.07"), "roth")),
                null);

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

        var result1 = new ProjectionResultResponse(id1, List.of(), BigDecimal.ZERO, 0);
        var result2 = new ProjectionResultResponse(id2, List.of(), BigDecimal.ZERO, 0);
        when(projectionEngine.run(scenario1)).thenReturn(result1);
        when(projectionEngine.run(scenario2)).thenReturn(result2);

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
                null, null, null, null, null,
                List.of(new CreateProjectionAccountRequest(
                        null, new BigDecimal("200000"),
                        new BigDecimal("15000"), new BigDecimal("0.08"), "traditional")),
                null);

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
                null, null, null, List.of(), null);

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
                null, null, null,
                List.of(
                        new CreateProjectionAccountRequest(null, new BigDecimal("200000"),
                                new BigDecimal("10000"), new BigDecimal("0.07"), "traditional"),
                        new CreateProjectionAccountRequest(null, new BigDecimal("100000"),
                                new BigDecimal("5000"), new BigDecimal("0.07"), "roth")),
                null);

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

        var engineResult = new ProjectionResultResponse(
                scenarioId,
                List.of(ProjectionYearDto.simple(2026, 36, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, false)),
                BigDecimal.ZERO, 0);
        when(projectionEngine.run(scenario)).thenReturn(engineResult);

        var result = service.runProjection(tenantId, scenarioId);

        assertThat(result).isEqualTo(engineResult);
        verify(projectionEngine).run(scenario);
    }

    @Test
    void runProjection_withLinkedAccount_resolvesCurrentBalance() {
        var linkedAccountId = UUID.randomUUID();
        var linkedAccount = new AccountEntity(tenant, "Brokerage", "brokerage", "Fidelity");

        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario, linkedAccount, new BigDecimal("100000"),
                new BigDecimal("10000"), new BigDecimal("0.07"), "taxable");
        scenario.addAccount(projAcct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var currentBalance = new BigDecimal("150000.00");
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(currentBalance);

        var engineResult = new ProjectionResultResponse(
                scenarioId, List.of(), BigDecimal.ZERO, 0);
        when(projectionEngine.run(scenario)).thenReturn(engineResult);

        service.runProjection(tenantId, scenarioId);

        assertThat(projAcct.getInitialBalance()).isEqualByComparingTo(currentBalance);
        verify(projectionEngine).run(scenario);
    }

    @Test
    void compareScenarios_withLinkedAccount_resolvesCurrentBalance() {
        var linkedAccountId = UUID.randomUUID();
        var linkedAccount = new AccountEntity(tenant, "401k", "401k", "Fidelity");

        var id1 = UUID.randomUUID();
        var scenario1 = new ProjectionScenarioEntity(tenant, "Plan A",
                LocalDate.of(2055, 1, 1), 90, new BigDecimal("0.03"), null);
        var projAcct = new ProjectionAccountEntity(
                scenario1, linkedAccount, new BigDecimal("50000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "traditional");
        scenario1.addAccount(projAcct);

        when(scenarioRepository.findByTenant_IdAndId(tenantId, id1))
                .thenReturn(Optional.of(scenario1));

        var currentBalance = new BigDecimal("75000.00");
        when(accountService.computeBalance(linkedAccount, tenantId))
                .thenReturn(currentBalance);

        var result1 = new ProjectionResultResponse(id1, List.of(), BigDecimal.ZERO, 0);
        when(projectionEngine.run(scenario1)).thenReturn(result1);

        service.compareScenarios(tenantId, new CompareRequest(List.of(id1)));

        assertThat(projAcct.getInitialBalance()).isEqualByComparingTo(currentBalance);
    }
}
