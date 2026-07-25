package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.GuardrailYearlySpending;
import com.wealthview.core.projection.dto.HypotheticalAccountInput;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.entity.TenantEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;

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
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"),
                phases,
                List.of(new GuardrailYearlySpending(
                        2030, 62, new BigDecimal("75000"), new BigDecimal("62000"),
                        new BigDecimal("91000"), new BigDecimal("30000"),
                        new BigDecimal("45000"), new BigDecimal("12000"),
                        new BigDecimal("63000"), "Early")),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);

        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Optimized Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"), phases,
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        var result = service.optimize(tenantId, scenarioId, request);

        assertThat(result).isNotNull();
        verify(spendingOptimizer).optimize(any(GuardrailOptimizationInput.class));
        verify(guardrailRepository).save(any(GuardrailSpendingProfileEntity.class));
    }

    // Audit C6: floor-clamp disclosure isn't persisted (like fixedReturnShare) -- optimize() must
    // thread it straight from the fresh spendingOptimizer.optimize() result into the final response
    // rather than losing it in the save/re-hydrate round trip through GuardrailProfileResponse.from.
    @Test
    void optimize_optimizerDisclosesFloorClamp_threadsBothFieldsIntoFinalResponse() {
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

        var phases = List.of(new GuardrailPhaseInput("All", 62, null, 1));

        var optimizerResponse = new GuardrailProfileResponse(
                null, scenarioId, "Optimized Plan",
                new BigDecimal("80000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                500, new BigDecimal("0.95"),
                phases,
                List.of(),
                new BigDecimal("0"), new BigDecimal("0"), new BigDecimal("1.0000"),
                new BigDecimal("0"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null,
                new GuardrailProfileResponse.Disclosure(null, true, new BigDecimal("0.0000"), null,
                        GuardrailProfileResponse.GATED_ON_NO_ADAPTATION));

        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Optimized Plan",
                new BigDecimal("80000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                500, new BigDecimal("0.95"), phases,
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        var result = service.optimize(tenantId, scenarioId, request);

        assertThat(result.floorReduced()).isTrue();
        assertThat(result.originalFloorSuccessProbability()).isEqualByComparingTo("0.0000");
    }

    @Test
    void optimize_nullEssentialFloor_defaultsToZero() {
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
                null, scenarioId, null, BigDecimal.ZERO, BigDecimal.ZERO,
                new BigDecimal("0.10"), 5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"), false,
                OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // A minimal request body (every field null) — the optimize endpoint must
        // not NPE; essentialFloor should fall back to ZERO like the other knobs.
        var request = new GuardrailOptimizationRequest(
                scenarioId, null, null, null,
                null, null, null, null,
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        service.optimize(tenantId, scenarioId, request);

        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        verify(spendingOptimizer).optimize(captor.capture());
        assertThat(captor.getValue().essentialFloor()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void optimize_scenarioNotFound_throws() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, List.of(),
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
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "New Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, List.of(),
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
        // A3: a persisted profile fetched without live account data can't recompute the
        // fixed-return override share, so it stays undisclosed (null) rather than lying with 0.
        assertThat(result.fixedReturnShare()).isNull();
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
        var hash1 = GuardrailProfileService.computeScenarioHash(scenario, List.of());
        var hash2 = GuardrailProfileService.computeScenarioHash(scenario, List.of());

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).isNotBlank();
    }

    @Test
    void computeScenarioHash_differentInputs_differentHash() {
        var scenario2 = new ProjectionScenarioEntity(
                tenant, "Other", LocalDate.of(2032, 1, 1), 95,
                new BigDecimal("0.02"), "{\"birth_year\":1970}");

        var hash1 = GuardrailProfileService.computeScenarioHash(scenario, List.of());
        var hash2 = GuardrailProfileService.computeScenarioHash(scenario2, List.of());

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

        var hash1 = GuardrailProfileService.computeScenarioHash(scenarioMinimal, List.of());
        var hash2 = GuardrailProfileService.computeScenarioHash(scenarioWithExtras, List.of());

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
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Guardrail", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, List.of(),
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
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"),
                List.of(new GuardrailPhaseInput("Early", 62, 72, 3)),
                List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
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
    void reoptimize_storedGateOnAdaptiveRulesTrue_honorsFlagOnInput() {
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Existing Plan", new BigDecimal("30000"));
        entity.setPhases("[]");
        entity.setYearlySpending("[]");
        entity.setScenarioHash("old-hash");
        entity.setReturnMean(new BigDecimal("0.10"));
        entity.setTrialCount(5000);
        entity.setConfidenceLevel(new BigDecimal("0.95"));
        entity.setTerminalBalanceTarget(BigDecimal.ZERO);
        entity.setGateOnAdaptiveRules(true);

        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.of(entity));
        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.reoptimize(tenantId, scenarioId);

        assertThat(captor.getValue().gateOnAdaptiveRules()).isTrue();
    }

    @Test
    void reoptimize_storedGateOnAdaptiveRulesFalse_honorsFlagOnInput() {
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Existing Plan", new BigDecimal("30000"));
        entity.setPhases("[]");
        entity.setYearlySpending("[]");
        entity.setScenarioHash("old-hash");
        entity.setReturnMean(new BigDecimal("0.10"));
        entity.setTrialCount(5000);
        entity.setConfidenceLevel(new BigDecimal("0.95"));
        entity.setTerminalBalanceTarget(BigDecimal.ZERO);
        // gateOnAdaptiveRules left at its default (false).

        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.of(entity));
        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.reoptimize(tenantId, scenarioId);

        assertThat(captor.getValue().gateOnAdaptiveRules()).isFalse();
    }

    // ---- stochastic mortality (sub-project B, T3): raw params + resolved table flow through
    // buildOptimizationInput into GuardrailOptimizationInput unchanged ----

    @Test
    void optimize_stochasticMortalityFieldsSet_passThroughToOptimizationInput() {
        var stochasticScenario = new ProjectionScenarioEntity(
                tenant, "Test Scenario", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"stochastic_mortality\":true,"
                        + "\"primary_sex\":\"male\",\"spouse_sex\":\"female\","
                        + "\"longevity_conditional_age\":100}");
        var table = new com.wealthview.core.projection.mortality.MortalityTable(
                Map.of(70, 0.02), Map.of(70, 0.01));
        when(projectionInputBuilder.resolveMortalityTable(any())).thenReturn(table);

        var input = captureOptimizationInput(buildRequest(req -> req), stochasticScenario);

        assertThat(input.stochasticMortality()).isTrue();
        assertThat(input.primarySex()).isEqualTo("male");
        assertThat(input.spouseSex()).isEqualTo("female");
        assertThat(input.longevityConditionalAge()).isEqualTo(100);
        assertThat(input.mortalityTable()).isSameAs(table);
    }

    @Test
    void optimize_stochasticMortalityAbsent_passesNullFieldsAndNullTable() {
        var input = captureOptimizationInput(buildRequest(req -> req));

        assertThat(input.stochasticMortality()).isNull();
        assertThat(input.primarySex()).isNull();
        assertThat(input.spouseSex()).isNull();
        assertThat(input.longevityConditionalAge()).isNull();
        assertThat(input.mortalityTable()).isNull();
    }

    @Test
    void reoptimize_notFound_throws() {
        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.reoptimize(tenantId, scenarioId))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- request-validation branches (optimizeConversions) ----

    @Test
    void optimize_optimizeConversionsRmdRateAboveConversionRate_throws() {
        var request = buildRequest(
                req -> req.withConvertOptimization(true)
                        .withConversionBracketRate(new BigDecimal("0.22"))
                        .withRmdTargetBracketRate(new BigDecimal("0.32")));

        assertThatThrownBy(() -> service.optimize(tenantId, scenarioId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RMD target bracket rate");
    }

    @Test
    void optimize_optimizeConversionsBufferBelowMin_throws() {
        var request = buildRequest(
                req -> req.withConvertOptimization(true).withBuffer(0));

        assertThatThrownBy(() -> service.optimize(tenantId, scenarioId, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Traditional exhaustion buffer");
    }

    @Test
    void optimize_optimizeConversionsBufferAboveMax_throws() {
        var request = buildRequest(
                req -> req.withConvertOptimization(true).withBuffer(16));

        assertThatThrownBy(() -> service.optimize(tenantId, scenarioId, request))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- confidence / risk tolerance resolution ----

    @Test
    void resolveConfidence_moderateRiskTolerance_returnsNinetyPercent() {
        var request = new GuardrailOptimizationRequest(
                scenarioId, "Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, List.of(),
                null, null, null, "moderate",
                null, null,
                null, null, null, null, null, null);
        assertThat(service.resolveConfidence(request)).isEqualByComparingTo(new BigDecimal("0.90"));
    }

    @Test
    void resolveConfidence_explicitConfidence_overridesPreset() {
        var request = new GuardrailOptimizationRequest(
                scenarioId, "Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, new BigDecimal("0.97"), List.of(),
                null, null, null, "aggressive",
                null, null,
                null, null, null, null, null, null);
        assertThat(service.resolveConfidence(request)).isEqualByComparingTo(new BigDecimal("0.97"));
    }

    @Test
    void optimize_riskToleranceConservative_setsConfidence095() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("conservative")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.95");
    }

    @Test
    void optimize_riskToleranceModerate_setsConfidence090() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("moderate")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.90");
    }

    @Test
    void optimize_riskToleranceAggressive_setsConfidence080() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("aggressive")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.80");
    }

    @Test
    void optimize_riskToleranceUnknown_fallsBackToDefaultConfidence() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("space-tourism")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.95");
    }

    @Test
    void optimize_noConfidenceAndNoRiskTolerance_fallsBackToDefault() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance(null)));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.95");
    }

    @Test
    void optimize_explicitConfidenceProvided_overridesRiskTolerance() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(new BigDecimal("0.88"))
                        .withRiskTolerance("conservative")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.88");
    }

    // ---- optional-field defaults ----

    @Test
    void optimize_nullOptionalFields_fillDefaults() {
        var input = captureOptimizationInput(buildRequest(req -> req
                .withReturnMean(null).withTrialCount(null)
                .withPortfolioFloor(null).withMaxAnnualAdjustmentRate(null)
                .withPhaseBlendYears(null).withCashReserveYears(null)
                .withCashReturnRate(null).withTerminalBalanceTarget(null)
                .withPhases(null)));

        // Audit C4: an omitted return_mean must flow through as NULL so the projection engine
        // derives the scenario's fee-adjusted, allocation-blended real return
        // (OptimizationContextBuilder.resolveReturnMean). Pre-filling 0.10 nominal here made the
        // engine's default branch dead code for all production traffic (the frontend never sends
        // return_mean) and re-introduced the frame mismatch the engine fix removed.
        assertThat(input.returnMean()).isNull();
        assertThat(input.trialCount()).isEqualTo(5000);
        assertThat(input.portfolioFloor()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(input.maxAnnualAdjustmentRate()).isEqualByComparingTo("0.05");
        assertThat(input.phaseBlendYears()).isEqualTo(1);
        assertThat(input.cashReserveYears()).isEqualTo(2);
        // Real terms: the cash-reserve sleeve return default is a REAL rate (~4% nominal deflated).
        assertThat(input.cashReturnRate()).isEqualByComparingTo("0.015");
        assertThat(input.terminalBalanceTarget()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(input.phases()).isEmpty();
    }

    // ---- T24: gate_on_adaptive_rules plumbing ----

    // Deliberate re-baseline (V077, user decision): an OMITTED toggle now resolves TRUE -- new
    // optimizations gate on the with-rules metric by default. The pre-V077 pin asserted false here.
    @Test
    void optimize_gateOnAdaptiveRulesOmitted_defaultsToTrueOnInput() {
        var input = captureOptimizationInput(buildRequest(req -> req.withGateOnAdaptiveRules(null)));

        assertThat(input.gateOnAdaptiveRules()).isTrue();
    }

    // Anchor pin (unchanged by V077): explicit false is fully honored -- the conservative,
    // byte-identical-to-pre-T24 no-adaptation-gate path.
    @Test
    void optimize_gateOnAdaptiveRulesFalseExplicitly_staysFalseOnInput() {
        var input = captureOptimizationInput(buildRequest(req -> req.withGateOnAdaptiveRules(false)));

        assertThat(input.gateOnAdaptiveRules()).isFalse();
    }

    // V077 default path end-to-end at the service layer: omitted toggle + positive adjustment rate
    // => the persisted entity carries true and the response reports gated_on == with_rules.
    @Test
    void optimize_gateOnAdaptiveRulesOmittedWithPositiveRate_responseGatedOnWithRules() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.empty());
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(baseOptimizerResponse());
        var savedCaptor = ArgumentCaptor.forClass(GuardrailSpendingProfileEntity.class);
        when(guardrailRepository.save(savedCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var request = buildRequest(req -> req.withGateOnAdaptiveRules(null)
                .withMaxAnnualAdjustmentRate(new BigDecimal("0.10")));
        var result = service.optimize(tenantId, scenarioId, request);

        assertThat(savedCaptor.getValue().isGateOnAdaptiveRules()).isTrue();
        assertThat(result.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_WITH_RULES);
    }

    @Test
    void optimize_gateOnAdaptiveRulesTrue_propagatesToInputEntityAndResponse() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.empty());
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(baseOptimizerResponse());
        var savedCaptor = ArgumentCaptor.forClass(GuardrailSpendingProfileEntity.class);
        when(guardrailRepository.save(savedCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        var request = buildRequest(req -> req.withGateOnAdaptiveRules(true)
                .withMaxAnnualAdjustmentRate(new BigDecimal("0.10")));
        var result = service.optimize(tenantId, scenarioId, request);

        // Entity round-trip: the toggle persists on the saved entity ...
        assertThat(savedCaptor.getValue().isGateOnAdaptiveRules()).isTrue();
        // ... and the final response (re-hydrated from that SAME saved entity, per
        // GuardrailProfileResponse.from) echoes gated_on = with_rules, since the entity's
        // max_annual_adjustment_rate is positive.
        assertThat(result.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_WITH_RULES);
    }

    @Test
    void optimize_gateOnAdaptiveRulesTrueButZeroAdjustmentRate_responseGatedOnStaysNoAdaptation() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.empty());
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = buildRequest(req -> req.withGateOnAdaptiveRules(true)
                .withMaxAnnualAdjustmentRate(BigDecimal.ZERO));
        var result = service.optimize(tenantId, scenarioId, request);

        // The toggle is on, but a zero adjustment rate makes the rule a no-op -- gated_on reflects
        // what ACTUALLY certified the run, not raw user intent.
        assertThat(result.gatedOn()).isEqualTo(GuardrailProfileResponse.GATED_ON_NO_ADAPTATION);
    }

    @Test
    void optimize_explicitReturnMean_passesThroughUnchanged() {
        // Explicit API-supplied return_mean keeps its wire contract: NOMINAL, forwarded verbatim;
        // the engine Fisher-converts it exactly once (resolveReturnMean's override branch).
        var input = captureOptimizationInput(
                buildRequest(req -> req.withReturnMean(new BigDecimal("0.08"))));

        assertThat(input.returnMean()).isEqualByComparingTo("0.08");
    }

    @Test
    void optimize_returnMeanOmitted_persistsOptimizerResolvedRate() {
        // Audit C4: the persisted return_mean column (and thus the response on reload) must carry
        // the RESOLVED effective REAL rate the optimizer actually used — echoed back on
        // GuardrailProfileResponse.returnMean — not the raw (null) request value and not a
        // hardcoded 0.10 default.
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.empty());

        var resolvedReal = new BigDecimal("0.0658");
        var optimizerResponse = new GuardrailProfileResponse(
                null, scenarioId, "Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                resolvedReal,
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(optimizerResponse);
        var savedCaptor = ArgumentCaptor.forClass(GuardrailSpendingProfileEntity.class);
        when(guardrailRepository.save(savedCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.optimize(tenantId, scenarioId, buildRequest(req -> req.withReturnMean(null)));

        assertThat(savedCaptor.getValue().getReturnMean()).isEqualByComparingTo(resolvedReal);
    }

    @Test
    void reoptimize_storedResolvedReturnMean_notEchoedBackAsNominalRequest() {
        // Audit C4 double-Fisher trap: the persisted return_mean is the RESOLVED REAL rate. Feeding
        // it back into the request (whose returnMean contract is NOMINAL) would Fisher-convert an
        // already-real rate a second time. reoptimize must pass null so the engine re-derives the
        // rate from the scenario's CURRENT allocation — which also picks up allocation edits, the
        // whole point of reoptimizing a stale profile.
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Existing Plan", new BigDecimal("30000"));
        entity.setPhases("[]");
        entity.setYearlySpending("[]");
        entity.setScenarioHash("old-hash");
        entity.setReturnMean(new BigDecimal("0.0658")); // resolved REAL rate from the prior run
        entity.setTrialCount(5000);
        entity.setConfidenceLevel(new BigDecimal("0.95"));
        entity.setTerminalBalanceTarget(BigDecimal.ZERO);

        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.of(entity));

        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.reoptimize(tenantId, scenarioId);

        assertThat(captor.getValue().returnMean())
                .as("stored resolved-real return_mean must NOT round-trip as a nominal request value")
                .isNull();
    }

    @Test
    void optimize_nullScenarioFields_fallBackToHardcodedDefaults() {
        var scenarioSansDefaults = new ProjectionScenarioEntity(
                tenant, "Empty", LocalDate.of(2030, 1, 1), null, null, null);
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioSansDefaults);
        assertThat(input.endAge()).isEqualTo(90);
        // Real terms: inflation defaults to 2.5% (matches the deterministic engine).
        assertThat(input.inflationRate()).isEqualByComparingTo(new BigDecimal("0.025"));
    }

    // ---- paramsJson parsing ----

    @Test
    void optimize_paramsJsonWithFilingStatusAndWithdrawalOrder_propagatesToInput() {
        var scenarioRich = new ProjectionScenarioEntity(
                tenant, "Rich", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"filing_status\":\"married_filing_jointly\"," +
                        "\"withdrawal_order\":\"traditional_first\"}");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioRich);
        assertThat(input.filingStatus()).isEqualTo("married_filing_jointly");
        assertThat(input.withdrawalOrder()).isEqualTo("traditional_first");
    }

    // A5 (2026-07-11 audit): the scenario's dividend_yield param must reach the MC's
    // GuardrailOptimizationInput (previously OptimizationContextBuilder hardcoded 1.8% and ignored
    // it entirely). Absent ⇒ null passed through — OptimizationContextBuilder applies the 0.018
    // default (see OptimizationContextBuilderTest).
    @Test
    void optimize_paramsJsonWithDividendYield_propagatesToInput() {
        var scenarioWithYield = new ProjectionScenarioEntity(
                tenant, "Yield", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"dividend_yield\":0.032}");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioWithYield);
        assertThat(input.dividendYield()).isEqualByComparingTo("0.032");
    }

    @Test
    void optimize_paramsJsonWithoutDividendYield_propagatesNull() {
        var input = captureOptimizationInput(buildRequest(req -> req), scenario);
        assertThat(input.dividendYield()).isNull();
    }

    // B1 (2026-07-11 audit): the scenario's fee_rate param must reach the MC's
    // GuardrailOptimizationInput the same way dividend_yield does. Absent ⇒ null passed through --
    // OptimizationContextBuilder applies the 0.0025 default (see OptimizationContextBuilderTest).
    @Test
    void optimize_paramsJsonWithFeeRate_propagatesToInput() {
        var scenarioWithFee = new ProjectionScenarioEntity(
                tenant, "Fee", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"fee_rate\":0.008}");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioWithFee);
        assertThat(input.feeRate()).isEqualByComparingTo("0.008");
    }

    @Test
    void optimize_paramsJsonWithoutFeeRate_propagatesNull() {
        var input = captureOptimizationInput(buildRequest(req -> req), scenario);
        assertThat(input.feeRate()).isNull();
    }

    // C1 (2026-07-12 audit): the scenario's interest_yield param must reach the MC's
    // GuardrailOptimizationInput the same way dividend_yield/fee_rate do. Absent -> null passed
    // through -- OptimizationContextBuilder applies the 0.04 default (see
    // ScenarioParamsParserTest / OptimizationContextBuilderTest).
    @Test
    void optimize_paramsJsonWithInterestYield_propagatesToInput() {
        var scenarioWithInterest = new ProjectionScenarioEntity(
                tenant, "Interest", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"interest_yield\":0.05}");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioWithInterest);
        assertThat(input.interestYield()).isEqualByComparingTo("0.05");
    }

    @Test
    void optimize_paramsJsonWithoutInterestYield_propagatesNull() {
        var input = captureOptimizationInput(buildRequest(req -> req), scenario);
        assertThat(input.interestYield()).isNull();
    }

    @Test
    void optimize_paramsJsonNull_birthYearDefaultsFromCurrentYear() {
        var scenarioSansParams = new ProjectionScenarioEntity(
                tenant, "None", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), null);
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioSansParams);
        assertThat(input.birthYear()).isEqualTo(java.time.LocalDate.now().getYear() - 35);
        assertThat(input.withdrawalOrder()).isEqualTo("taxable_first");
        assertThat(input.filingStatus()).isNull();
    }

    @Test
    void optimize_paramsJsonBlank_birthYearDefaultsFromCurrentYear() {
        var scenarioBlank = new ProjectionScenarioEntity(
                tenant, "Blank", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "   ");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioBlank);
        assertThat(input.birthYear()).isEqualTo(java.time.LocalDate.now().getYear() - 35);
    }

    @Test
    void optimize_paramsJsonMissingBirthYear_fallsBack() {
        var scenarioNoBy = new ProjectionScenarioEntity(
                tenant, "NoBy", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"filing_status\":\"single\"}");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioNoBy);
        assertThat(input.birthYear()).isEqualTo(java.time.LocalDate.now().getYear() - 35);
    }

    @Test
    void optimize_paramsJsonMalformed_fallsBackQuietly() {
        var scenarioBad = new ProjectionScenarioEntity(
                tenant, "Bad", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{not-json");
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioBad);
        assertThat(input.birthYear()).isEqualTo(java.time.LocalDate.now().getYear() - 35);
        assertThat(input.filingStatus()).isNull();
    }

    // ---- conversion schedule serialization ----

    @Test
    void optimize_optimizerReturnsConversionSchedule_persistsIt() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.empty());

        var schedule = new com.wealthview.core.projection.dto.RothConversionScheduleResponse(
                new BigDecimal("50000"), new BigDecimal("75000"), new BigDecimal("25000"),
                85, true, new BigDecimal("0.24"), new BigDecimal("0.22"), 5,
                new BigDecimal("0.10"), new BigDecimal("500000"), new BigDecimal("0.10"),
                List.of());

        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(withSchedule(baseOptimizerResponse(), schedule));
        var captor = ArgumentCaptor.forClass(GuardrailSpendingProfileEntity.class);
        when(guardrailRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        service.optimize(tenantId, scenarioId, buildRequest(req -> req));

        assertThat(captor.getValue().getConversionSchedule()).contains("50000");
    }

    // ---- reoptimize: malformed phases falls back ----

    @Test
    void reoptimize_malformedPhasesJson_fallsBackToEmptyList() {
        var entity = new GuardrailSpendingProfileEntity(
                tenant, scenario, "Plan", new BigDecimal("30000"));
        entity.setPhases("{not-json");
        entity.setYearlySpending("[]");
        entity.setReturnMean(new BigDecimal("0.10"));
        entity.setTrialCount(5000);
        entity.setConfidenceLevel(new BigDecimal("0.95"));
        entity.setTerminalBalanceTarget(BigDecimal.ZERO);

        when(guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId))
                .thenReturn(Optional.of(entity));
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.of(entity));
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var inputCaptor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);

        service.reoptimize(tenantId, scenarioId);

        verify(spendingOptimizer).optimize(inputCaptor.capture());
        assertThat(inputCaptor.getValue().phases()).isEmpty();
    }

    // ---- computeScenarioHash: malformed paramsJson is quietly ignored ----

    @Test
    void computeScenarioHash_includesAccountDetails() {
        var scenarioWithAccount = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}");
        scenarioWithAccount.addAccount(new com.wealthview.persistence.entity.ProjectionAccountEntity(
                scenarioWithAccount, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "traditional"));
        var scenarioWithDifferentAccount = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}");
        scenarioWithDifferentAccount.addAccount(new com.wealthview.persistence.entity.ProjectionAccountEntity(
                scenarioWithDifferentAccount, null, new BigDecimal("200000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "traditional"));

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioWithAccount, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioWithDifferentAccount, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void optimize_optimizeConversionsBufferNull_defaultsToFive() {
        var input = captureOptimizationInput(buildRequest(req -> req
                .withConvertOptimization(true).withBuffer(null)
                .withConversionBracketRate(new BigDecimal("0.24"))
                .withRmdTargetBracketRate(new BigDecimal("0.22"))));
        assertThat(input.traditionalExhaustionBuffer()).isEqualTo(5);
    }

    @Test
    void computeScenarioHash_malformedParamsJson_hashesWithoutBirthYear() {
        var scenarioBadJson = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{broken");

        var hash = GuardrailProfileService.computeScenarioHash(scenarioBadJson, List.of());

        assertThat(hash).isNotBlank();
    }

    // ---- A5 (2026-07-11 audit): scenarioSignature must cover every realism-v2 MC input ----

    @Test
    void computeScenarioHash_allocationChanged_hashChanges() {
        var scenarioA = scenarioWithSingleAccount();
        scenarioA.getAccounts().getFirst().setAllocation(
                Map.of("us_stock", new BigDecimal("60"), "bond", new BigDecimal("40")));
        var scenarioB = scenarioWithSingleAccount();
        scenarioB.getAccounts().getFirst().setAllocation(
                Map.of("us_stock", new BigDecimal("80"), "bond", new BigDecimal("20")));

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_costBasisChanged_hashChanges() {
        var scenarioA = scenarioWithSingleAccount();
        scenarioA.getAccounts().getFirst().setCostBasis(new BigDecimal("80000"));
        var scenarioB = scenarioWithSingleAccount();
        scenarioB.getAccounts().getFirst().setCostBasis(new BigDecimal("95000"));

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_dividendYieldChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"dividend_yield\":0.018}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"dividend_yield\":0.03}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // B1 (2026-07-11 audit): fee_rate is a realism-v2 MC input (feeds PortfolioPathGenerator.generate)
    // that must stale-out an existing guardrail profile on edit, exactly like dividend_yield.
    @Test
    void computeScenarioHash_feeRateChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"fee_rate\":0.0025}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"fee_rate\":0.01}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // C1 (2026-07-12 audit): interest_yield is a realism-v2 MC input (splits the taxable pool's
    // yield by allocation) that must stale-out an existing guardrail profile on edit, exactly like
    // dividend_yield/fee_rate.
    @Test
    void computeScenarioHash_interestYieldChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"interest_yield\":0.04}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"interest_yield\":0.06}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_incomeSourceAdded_hashChanges() {
        var scenario = scenarioWithSingleAccount();
        var incomeSourceId = UUID.randomUUID();

        var hashWithout = GuardrailProfileService.computeScenarioHash(scenario, List.of());
        var hashWith = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(incomeSourceId, new BigDecimal("24000"))));

        assertThat(hashWithout).isNotEqualTo(hashWith);
    }

    @Test
    void computeScenarioHash_incomeSourceAmountChanged_hashChanges() {
        var scenario = scenarioWithSingleAccount();
        var incomeSourceId = UUID.randomUUID();

        var hashA = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(incomeSourceId, new BigDecimal("24000"))));
        var hashB = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(incomeSourceId, new BigDecimal("30000"))));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // Household/survivor modeling (sub-project A, T3): every new household field, account owner,
    // and income-source owner/survivor_percent must be part of the guardrail staleness signature.

    @Test
    void computeScenarioHash_spouseBirthYearChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1972}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_primaryDeathAgeChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"primary_death_age\":85}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"primary_death_age\":95}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // The signature hashes the RESOLVED death age (explicit override, or the SSA planning default
    // for the birth year), not the raw possibly-null field -- an explicit death age that happens
    // to equal the default must hash identically to leaving it unset, since the engine treats them
    // identically. LifeExpectancy.defaultDeathAge(1968) == 87.
    @Test
    void computeScenarioHash_explicitDeathAgeMatchingSsaDefault_hashesSameAsUnset() {
        var scenarioUnset = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968}");
        var scenarioExplicitDefault = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"primary_death_age\":87}");

        var hashUnset = GuardrailProfileService.computeScenarioHash(scenarioUnset, List.of());
        var hashExplicit = GuardrailProfileService.computeScenarioHash(scenarioExplicitDefault, List.of());

        assertThat(hashUnset).isEqualTo(hashExplicit);
    }

    @Test
    void computeScenarioHash_survivorSpendingFactorChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"survivor_spending_factor\":0.75}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"survivor_spending_factor\":0.9}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_communityPropertyChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"community_property\":false}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"community_property\":true}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // Stochastic mortality (sub-project B, T3): every new field must be part of the guardrail
    // staleness signature, mirroring the household field tests above.

    @Test
    void computeScenarioHash_stochasticMortalityToggled_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"stochastic_mortality\":false}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"stochastic_mortality\":true}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_primarySexChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"stochastic_mortality\":true,\"primary_sex\":\"male\"}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"stochastic_mortality\":true,\"primary_sex\":\"female\"}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_spouseSexChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"stochastic_mortality\":true,"
                        + "\"spouse_sex\":\"male\"}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"spouse_birth_year\":1970,\"stochastic_mortality\":true,"
                        + "\"spouse_sex\":\"female\"}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_longevityConditionalAgeChanged_hashChanges() {
        var scenarioA = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"stochastic_mortality\":true,\"longevity_conditional_age\":95}");
        var scenarioB = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"),
                "{\"birth_year\":1968,\"stochastic_mortality\":true,\"longevity_conditional_age\":100}");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_accountOwnerChanged_hashChanges() {
        var scenarioA = scenarioWithSingleAccount();
        scenarioA.getAccounts().getFirst().setOwner("primary");
        var scenarioB = scenarioWithSingleAccount();
        scenarioB.getAccounts().getFirst().setOwner("spouse");

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioA, List.of());
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioB, List.of());

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_incomeSourceOwnerChanged_hashChanges() {
        var scenario = scenarioWithSingleAccount();
        var incomeSourceId = UUID.randomUUID();

        var hashA = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(
                        incomeSourceId, new BigDecimal("24000"), "primary", BigDecimal.ONE)));
        var hashB = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(
                        incomeSourceId, new BigDecimal("24000"), "spouse", BigDecimal.ONE)));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    @Test
    void computeScenarioHash_incomeSourceSurvivorPercentChanged_hashChanges() {
        var scenario = scenarioWithSingleAccount();
        var incomeSourceId = UUID.randomUUID();

        var hashA = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(
                        incomeSourceId, new BigDecimal("24000"), "primary", BigDecimal.ONE)));
        var hashB = GuardrailProfileService.computeScenarioHash(scenario, List.of(
                new GuardrailProfileService.IncomeSourceSignature(
                        incomeSourceId, new BigDecimal("24000"), "primary", new BigDecimal("0.5"))));

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // The persisted `accounts` collection is an unordered JPA bag; @OrderBy("id") keeps normal
    // reads stable, and scenarioSignature also sorts defensively by id, so the signature (and the
    // seed derived from it) must not depend on collection iteration order.
    @Test
    void computeScenarioHash_accountCollectionReordered_hashUnchanged() {
        var idOne = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var idTwo = UUID.fromString("00000000-0000-0000-0000-000000000002");

        var scenarioForward = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}");
        var acctOneForward = accountWithId(scenarioForward, idOne, "100000", "traditional");
        var acctTwoForward = accountWithId(scenarioForward, idTwo, "200000", "taxable");
        scenarioForward.addAccount(acctOneForward);
        scenarioForward.addAccount(acctTwoForward);

        var scenarioReversed = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}");
        var acctTwoReversed = accountWithId(scenarioReversed, idTwo, "200000", "taxable");
        var acctOneReversed = accountWithId(scenarioReversed, idOne, "100000", "traditional");
        scenarioReversed.addAccount(acctTwoReversed);
        scenarioReversed.addAccount(acctOneReversed);

        var hashForward = GuardrailProfileService.computeScenarioHash(scenarioForward, List.of());
        var hashReversed = GuardrailProfileService.computeScenarioHash(scenarioReversed, List.of());

        assertThat(hashForward).isEqualTo(hashReversed);
    }

    private ProjectionScenarioEntity scenarioWithSingleAccount() {
        var scenario = new ProjectionScenarioEntity(
                tenant, "x", LocalDate.of(2030, 1, 1), 90, new BigDecimal("0.03"), "{\"birth_year\":1968}");
        scenario.addAccount(new ProjectionAccountEntity(
                scenario, null, new BigDecimal("100000"),
                new BigDecimal("5000"), new BigDecimal("0.07"), "traditional"));
        return scenario;
    }

    private ProjectionAccountEntity accountWithId(
            ProjectionScenarioEntity scenario, UUID id, String balance, String accountType) {
        var account = new ProjectionAccountEntity(
                scenario, null, new BigDecimal(balance),
                new BigDecimal("5000"), new BigDecimal("0.07"), accountType);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    // ---- A3: fixedReturnShare disclosure ----

    @Test
    void computeFixedReturnShare_allAccountsOverride_returnsOne() {
        List<com.wealthview.core.projection.dto.ProjectionAccountInput> accounts = List.of(
                new HypotheticalAccountInput(
                        new BigDecimal("300000"), BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                new HypotheticalAccountInput(
                        new BigDecimal("200000"), BigDecimal.ZERO, new BigDecimal("0.06"), "traditional"));

        var share = GuardrailProfileService.computeFixedReturnShare(accounts);

        assertThat(share).isEqualByComparingTo("1.0000");
    }

    @Test
    void computeFixedReturnShare_noAccountsOverride_returnsZero() {
        List<com.wealthview.core.projection.dto.ProjectionAccountInput> accounts = List.of(
                new HypotheticalAccountInput(
                        new BigDecimal("300000"), BigDecimal.ZERO, null, "taxable"),
                new HypotheticalAccountInput(
                        new BigDecimal("200000"), BigDecimal.ZERO, null, "traditional"));

        var share = GuardrailProfileService.computeFixedReturnShare(accounts);

        assertThat(share).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeFixedReturnShare_mixed60_40ByBalance_returnsWeightedShare() {
        List<com.wealthview.core.projection.dto.ProjectionAccountInput> accounts = List.of(
                new HypotheticalAccountInput(
                        new BigDecimal("600000"), BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"),
                new HypotheticalAccountInput(
                        new BigDecimal("400000"), BigDecimal.ZERO, null, "traditional"));

        var share = GuardrailProfileService.computeFixedReturnShare(accounts);

        assertThat(share).isEqualByComparingTo("0.6000");
    }

    @Test
    void computeFixedReturnShare_zeroTotalBalance_returnsZeroWithoutDivideByZero() {
        List<com.wealthview.core.projection.dto.ProjectionAccountInput> accounts = List.of(
                new HypotheticalAccountInput(
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.07"), "taxable"));

        var share = GuardrailProfileService.computeFixedReturnShare(accounts);

        assertThat(share).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void computeFixedReturnShare_noAccounts_returnsZero() {
        var share = GuardrailProfileService.computeFixedReturnShare(List.of());

        assertThat(share).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void optimize_mixedOverrideAccounts_setsFixedReturnShareOnResponse() {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(scenario));
        when(projectionInputBuilder.build(scenario, tenantId))
                .thenReturn(new com.wealthview.core.projection.dto.ProjectionInput(
                        scenarioId, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                        new BigDecimal("0.03"), "{\"birth_year\":1968}",
                        List.of(
                                new HypotheticalAccountInput(
                                        new BigDecimal("600000"), BigDecimal.ZERO,
                                        new BigDecimal("0.07"), "taxable"),
                                new HypotheticalAccountInput(
                                        new BigDecimal("400000"), BigDecimal.ZERO,
                                        null, "traditional")),
                        null));
        when(guardrailRepository.findByScenario_Id(scenarioId))
                .thenReturn(Optional.empty());
        when(spendingOptimizer.optimize(any(GuardrailOptimizationInput.class)))
                .thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        var request = new GuardrailOptimizationRequest(
                scenarioId, "Plan", new BigDecimal("30000"), BigDecimal.ZERO,
                null, null, null, List.of(),
                null, null, null, null,
                null, null,
                null, null, null, null, null, null);

        var result = service.optimize(tenantId, scenarioId, request);

        assertThat(result.fixedReturnShare()).isEqualByComparingTo("0.6000");
    }

    // ---- deterministic seed derivation ----

    @Test
    void optimize_sameScenario_producesStableNonNullSeed() {
        var seedA = captureOptimizationInput(buildRequest(req -> req)).seed();
        var seedB = captureOptimizationInput(buildRequest(req -> req)).seed();

        assertThat(seedA).isNotNull().isEqualTo(seedB);
    }

    @Test
    void optimize_differentScenarioInflation_producesDifferentSeed() {
        var scenarioOtherInflation = new ProjectionScenarioEntity(
                tenant, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                new BigDecimal("0.04"), "{\"birth_year\":1968}");

        var seedA = captureOptimizationInput(buildRequest(req -> req), scenario).seed();
        var seedB = captureOptimizationInput(buildRequest(req -> req), scenarioOtherInflation).seed();

        assertThat(seedA).isNotEqualTo(seedB);
    }

    // ---- helpers ----

    private GuardrailOptimizationInput captureOptimizationInput(GuardrailOptimizationRequest request) {
        return captureOptimizationInput(request, scenario);
    }

    private GuardrailOptimizationInput captureOptimizationInput(GuardrailOptimizationRequest request,
                                                                 ProjectionScenarioEntity targetScenario) {
        when(scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId))
                .thenReturn(Optional.of(targetScenario));
        when(projectionInputBuilder.build(targetScenario, tenantId)).thenReturn(simpleProjectionInput());
        when(guardrailRepository.findByScenario_Id(scenarioId)).thenReturn(Optional.empty());
        var captor = ArgumentCaptor.forClass(GuardrailOptimizationInput.class);
        when(spendingOptimizer.optimize(captor.capture())).thenReturn(baseOptimizerResponse());
        when(guardrailRepository.save(any(GuardrailSpendingProfileEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        service.optimize(tenantId, scenarioId, request);
        return captor.getValue();
    }

    private com.wealthview.core.projection.dto.ProjectionInput simpleProjectionInput() {
        return new com.wealthview.core.projection.dto.ProjectionInput(
                scenarioId, "Test Scenario", LocalDate.of(2030, 1, 1), 90,
                new BigDecimal("0.03"), "{\"birth_year\":1968}",
                List.of(new HypotheticalAccountInput(
                        new BigDecimal("500000"), BigDecimal.ZERO,
                        new BigDecimal("0.07"), "taxable")),
                null);
    }

    private GuardrailProfileResponse baseOptimizerResponse() {
        return new GuardrailProfileResponse(
                null, scenarioId, "Plan",
                new BigDecimal("30000"), BigDecimal.ZERO,
                new BigDecimal("0.10"),
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"), new BigDecimal("0.95"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
    }

    private GuardrailProfileResponse withSchedule(GuardrailProfileResponse base,
            com.wealthview.core.projection.dto.RothConversionScheduleResponse schedule) {
        return new GuardrailProfileResponse(
                base.id(), base.scenarioId(), base.name(), base.essentialFloor(),
                base.terminalBalanceTarget(), base.returnMean(),
                base.trialCount(), base.confidenceLevel(), base.phases(), base.yearlySpending(),
                base.medianFinalBalance(), base.failureRate(), base.successProbability(),
                base.percentile10Final(),
                base.stale(), base.createdAt(), base.updatedAt(),
                base.portfolioFloor(), base.maxAnnualAdjustmentRate(),
                base.phaseBlendYears(), base.riskTolerance(),
                base.cashReserveYears(), base.cashReturnRate(), schedule);
    }

    private GuardrailOptimizationRequest buildRequest(java.util.function.Function<RequestBuilder, RequestBuilder> fn) {
        return fn.apply(new RequestBuilder(scenarioId)).build();
    }

    private static final class RequestBuilder {
        private final UUID scenarioId;
        private String name = "Plan";
        private BigDecimal essentialFloor = new BigDecimal("30000");
        private BigDecimal terminalBalanceTarget = BigDecimal.ZERO;
        private BigDecimal returnMean = new BigDecimal("0.10");
        private Integer trialCount = 5000;
        private BigDecimal confidence = new BigDecimal("0.95");
        private List<GuardrailPhaseInput> phases = List.of();
        private BigDecimal portfolioFloor = BigDecimal.ZERO;
        private BigDecimal maxAnnualAdjustmentRate = new BigDecimal("0.05");
        private Integer phaseBlendYears = 1;
        private String riskTolerance;
        private Integer cashReserveYears = 2;
        private BigDecimal cashReturnRate = new BigDecimal("0.04");
        private Boolean optimizeConversions = false;
        private BigDecimal conversionBracketRate;
        private BigDecimal rmdTargetBracketRate;
        private Integer buffer;
        private Boolean gateOnAdaptiveRules;

        RequestBuilder(UUID scenarioId) { this.scenarioId = scenarioId; }
        RequestBuilder withGateOnAdaptiveRules(Boolean v) { this.gateOnAdaptiveRules = v; return this; }
        RequestBuilder withConfidence(BigDecimal v) { this.confidence = v; return this; }
        RequestBuilder withRiskTolerance(String v) { this.riskTolerance = v; return this; }
        RequestBuilder withReturnMean(BigDecimal v) { this.returnMean = v; return this; }
        RequestBuilder withTrialCount(Integer v) { this.trialCount = v; return this; }
        RequestBuilder withPortfolioFloor(BigDecimal v) { this.portfolioFloor = v; return this; }
        RequestBuilder withMaxAnnualAdjustmentRate(BigDecimal v) { this.maxAnnualAdjustmentRate = v; return this; }
        RequestBuilder withPhaseBlendYears(Integer v) { this.phaseBlendYears = v; return this; }
        RequestBuilder withCashReserveYears(Integer v) { this.cashReserveYears = v; return this; }
        RequestBuilder withCashReturnRate(BigDecimal v) { this.cashReturnRate = v; return this; }
        RequestBuilder withTerminalBalanceTarget(BigDecimal v) { this.terminalBalanceTarget = v; return this; }
        RequestBuilder withPhases(List<GuardrailPhaseInput> v) { this.phases = v; return this; }
        RequestBuilder withConvertOptimization(boolean v) { this.optimizeConversions = v; return this; }
        RequestBuilder withConversionBracketRate(BigDecimal v) { this.conversionBracketRate = v; return this; }
        RequestBuilder withRmdTargetBracketRate(BigDecimal v) { this.rmdTargetBracketRate = v; return this; }
        RequestBuilder withBuffer(Integer v) { this.buffer = v; return this; }

        GuardrailOptimizationRequest build() {
            return new GuardrailOptimizationRequest(scenarioId, name, essentialFloor,
                    terminalBalanceTarget, returnMean, trialCount, confidence, phases,
                    portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears, riskTolerance,
                    cashReserveYears, cashReturnRate, optimizeConversions,
                    conversionBracketRate, rmdTargetBracketRate, buffer,
                    // rmdBracketHeadroom and dynamicSequencingBracketRate are always absent here:
                    // this builder covers GuardrailProfileService's own wiring, while those two are
                    // exercised against real values in RothConversionOptimizerTest.
                    null, null, gateOnAdaptiveRules);
        }
    }
}
