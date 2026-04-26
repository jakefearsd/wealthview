package com.wealthview.core.projection;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.CompareRequest;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ProjectionResultResponse;
import com.wealthview.core.projection.dto.ProjectionYearDto;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    @Mock
    private ProjectionScenarioRepository scenarioRepository;

    @Mock
    private ProjectionEngine projectionEngine;

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
}
