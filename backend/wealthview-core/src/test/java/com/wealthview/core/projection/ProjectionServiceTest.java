package com.wealthview.core.projection;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.CreateProjectionAccountRequest;
import com.wealthview.core.projection.dto.CreateScenarioRequest;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.AccountRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
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
    private ProjectionEngine projectionEngine;

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
                List.of(new CreateProjectionAccountRequest(
                        null,
                        new BigDecimal("100000"),
                        new BigDecimal("10000"),
                        new BigDecimal("0.07"))));

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
    void runProjection_exists_delegatesToEngine() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2055, 1, 1), 90,
                new BigDecimal("0.03"), null);
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        var engineResult = new ProjectionResultResponse(
                scenarioId,
                List.of(new ProjectionYearDto(2026, 36, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, false)),
                BigDecimal.ZERO, 0);
        when(projectionEngine.run(scenario)).thenReturn(engineResult);

        var result = service.runProjection(tenantId, scenarioId);

        assertThat(result).isEqualTo(engineResult);
        verify(projectionEngine).run(scenario);
    }
}
