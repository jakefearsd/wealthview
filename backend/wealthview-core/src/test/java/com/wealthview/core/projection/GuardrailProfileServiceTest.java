package com.wealthview.core.projection;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardrailProfileServiceTest {

    @Mock
    private GuardrailSpendingProfileRepository guardrailRepository;

    @Mock
    private ProjectionScenarioRepository scenarioRepository;

    @Mock
    private ProjectionInputBuilder projectionInputBuilder;

    @Mock
    private SpendingOptimizer spendingOptimizer;

    private GuardrailProfileService service;

    private UUID tenantId;
    private UUID scenarioId;
    private TenantEntity tenant;
    private ProjectionScenarioEntity scenario;

    @BeforeEach
    void setUp() {
        service = new GuardrailProfileService(
                guardrailRepository, scenarioRepository, projectionInputBuilder, spendingOptimizer);
        tenantId = UUID.randomUUID();
        scenarioId = UUID.randomUUID();
        tenant = new TenantEntity("Test");
        scenario = new ProjectionScenarioEntity(
                tenant, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1968}");
    }

    @Test
    void optimize_validRequest_callsOptimizerAndPersists() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId))
                .thenReturn(new com.wealthview.core.projection.dto.ProjectionInput(
                        scenarioId, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                        new BigDecimal("0.03"), "{\"birth_year\":1968}",
                        List.of(new HypotheticalAccountInput(
                                new BigDecimal("500000"), BigDecimal.ZERO,
                                new BigDecimal("0.07"), "taxable")),
                        null));
        when(guardrailRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.empty());

        var phases = List.of(
                new GuardrailPhaseInput("Early", 62, 72, 3),
                new GuardrailPhaseInput("Late", 73, null, 1));

        var optimizerResponse = new GuardrailProfileResponse(
                null, scenarioId, "Optimized Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"),
                phases,
                List.of(new GuardrailYearlySpending(
                        2030, 62, new BigDecimal("75000"), new BigDecimal("62000"),
                        new BigDecimal("91000"), new BigDecimal("30000"),
                        new BigDecimal("45000"), new BigDecimal("12000"),
                        new BigDecimal("63000"), "Early")),
                new BigDecimal("250000"), new BigDecimal("0.05"),
                new BigDecimal("100000"), new BigDecimal("500000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);

        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Optimized Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"), phases,
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        var result = service.optimize(tenantId, scenarioId, request);

        assertThat(result).isNotNull();
        verify(spendingOptimizer).optimize(any(GuardrailOptimizationInput.class));
        verify(guardrailRepository).save(any(GuardrailSpendingProfileEntity.class));
    }

    @Test
    void optimize_scenarioNotFound_throws() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        assertThatThrownBy(() -> service.optimize(tenantId, scenarioId, request))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void optimize_existingProfile_replacesIt() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId))
                .thenReturn(new com.wealthview.core.projection.dto.ProjectionInput(
                        scenarioId, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                        new BigDecimal("0.03"), "{\"birth_year\":1968}",
                        List.of(new HypotheticalAccountInput(
                                new BigDecimal("500000"), BigDecimal.ZERO,
                                new BigDecimal("0.07"), "taxable")),
                        null));

        var existing = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Old", new BigDecimal("25000"));
        when(guardrailRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.of(existing));

        var optimizerResponse = new GuardrailProfileResponse(
                null, scenarioId, "New Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"),
                new BigDecimal("100000"), new BigDecimal("500000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "New Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        service.optimize(tenantId, scenarioId, request);

        verify(guardrailRepository).delete(existing);
        verify(guardrailRepository).save(any(GuardrailSpendingProfileEntity.class));
    }

    @Test
    void getGuardrailProfile_exists_returnsProfile() {
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Test Plan", new BigDecimal("30000"));
        entity.setPhases("[]");
        entity.setYearlySpending("[]");
        entity.setScenarioHash("abc123");
        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));

        var result = service.getGuardrailProfile(tenantId, scenarioId);

        assertThat(result.name()).isEqualTo("Test Plan");
        assertThat(result.essentialFloor()).isEqualByComparingTo(new BigDecimal("30000"));
    }

    @Test
    void getGuardrailProfile_notFound_throws() {
        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getGuardrailProfile(tenantId, scenarioId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deleteGuardrailProfile_exists_deletesAndClearsScenarioFK() {
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Test Plan", new BigDecimal("30000"));
        entity.setScenarioHash("abc123");
        scenario.setGuardrailProfile(entity);
        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));

        service.deleteGuardrailProfile(tenantId, scenarioId);

        verify(guardrailRepository).delete(entity);
        assertThat(scenario.getGuardrailProfile()).isNull();
    }

    @Test
    void deleteGuardrailProfile_notFound_throws() {
        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGuardrailProfile(tenantId, scenarioId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void computeScenarioHash_sameInputs_samHash() {
        var hash1 = GuardrailProfileService.computeScenarioHash(scenario);
        var hash2 = GuardrailProfileService.computeScenarioHash(scenario);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotBlank();
    }

    @Test
    void computeScenarioHash_differentInputs_differentHash() {
        var scenario2 = new ProjectionScenarioEntity(
                tenant, "Other", LocalDate.of(2032, 1, 1), 95,
                new BigDecimal("0.02"), "{\"birth_year\":1970}");

        var hash1 = GuardrailProfileService.computeScenarioHash(scenario);
        var hash2 = GuardrailProfileService.computeScenarioHash(scenario2);

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void computeScenarioHash_irrelevantParamsDoNotChangeHash() {
        var scenarioMinimal = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2030, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1968}");
        var scenarioWithExtras = new ProjectionScenarioEntity(
                tenant, "Plan", LocalDate.of(2030, 1, 1), 90,
                new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"withdrawal_strategy\":\"vanguard_dynamic_spending\",\"filing_status\":\"married_filing_jointly\"}");

        var hash1 = GuardrailProfileService.computeScenarioHash(scenarioMinimal);
        var hash2 = GuardrailProfileService.computeScenarioHash(scenarioWithExtras);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void optimize_setsGuardrailProfileOnScenarioAndClearsSpendingProfile() {
        scenario.setSpendingProfile(new com.wealthview.persistence.entity.SpendingProfileEntity(
                tenant, "Manual", new BigDecimal("40000"), new BigDecimal("20000"), "[]"));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId))
                .thenReturn(new com.wealthview.core.projection.dto.ProjectionInput(
                        scenarioId, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                        new BigDecimal("0.03"), "{\"birth_year\":1968}",
                        List.of(new HypotheticalAccountInput(
                                new BigDecimal("500000"), BigDecimal.ZERO,
                                new BigDecimal("0.07"), "taxable")),
                        null));
        when(guardrailRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.empty());

        var optimizerResponse = new GuardrailProfileResponse(
                null, scenarioId, "Guardrail",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"),
                new BigDecimal("100000"), new BigDecimal("500000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Guardrail", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        service.optimize(tenantId, scenarioId, request);

        assertThat(scenario.getSpendingProfile()).isNull();
        verify(scenarioRepository).save(scenario);
    }

    @Test
    void reoptimize_existingProfile_rerunsWithStoredParams() {
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Existing Plan", new BigDecimal("30000"));
        entity.setPhases("""
                [{"name":"Early","startAge":62,"endAge":72,"priorityWeight":3}]
                """);
        entity.setYearlySpending("[]");
        entity.setScenarioHash("old-hash");
        entity.setReturnMean(new BigDecimal("0.10"));
        entity.setReturnStddev(new BigDecimal("0.15"));
        entity.setTrialCount(5000);
        entity.setConfidenceLevel(new BigDecimal("0.95"));
        entity.setTerminalBalanceTarget(BigDecimal.ZERO);

        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId))
                .thenReturn(new com.wealthview.core.projection.dto.ProjectionInput(
                        scenarioId, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                        new BigDecimal("0.03"), "{\"birth_year\":1968}",
                        List.of(new HypotheticalAccountInput(
                                new BigDecimal("500000"), BigDecimal.ZERO,
                                new BigDecimal("0.07"), "taxable")),
                        null));
        when(guardrailRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.of(entity));

        var optimizerResponse = new GuardrailProfileResponse(
                null, scenarioId, "Existing Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"),
                List.of(new GuardrailPhaseInput("Early", 62, 72, 3)),
                List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"),
                new BigDecimal("100000"), new BigDecimal("500000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var result = service.reoptimize(tenantId, scenarioId);

        assertThat(result).isNotNull();
        verify(spendingOptimizer).optimize(any(GuardrailOptimizationInput.class));
    }

    @Test
    void reoptimize_notFound_throws() {
        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reoptimize(tenantId, scenarioId))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
