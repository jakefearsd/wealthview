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
                new BigDecimal("100000"),
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
                new BigDecimal("100000"),
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
    void optimize_riskToleranceConservative_setsConfidence085() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("conservative")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.85");
    }

    @Test
    void optimize_riskToleranceModerate_setsConfidence070() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("moderate")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.70");
    }

    @Test
    void optimize_riskToleranceAggressive_setsConfidence060() {
        var input = captureOptimizationInput(
                buildRequest(req -> req.withConfidence(null).withRiskTolerance("aggressive")));
        assertThat(input.confidenceLevel()).isEqualByComparingTo("0.60");
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
                .withReturnMean(null).withReturnStddev(null).withTrialCount(null)
                .withPortfolioFloor(null).withMaxAnnualAdjustmentRate(null)
                .withPhaseBlendYears(null).withCashReserveYears(null)
                .withCashReturnRate(null).withTerminalBalanceTarget(null)
                .withPhases(null)));

        assertThat(input.returnMean()).isEqualByComparingTo("0.10");
        assertThat(input.returnStddev()).isEqualByComparingTo("0.15");
        assertThat(input.trialCount()).isEqualTo(5000);
        assertThat(input.portfolioFloor()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(input.maxAnnualAdjustmentRate()).isEqualByComparingTo("0.05");
        assertThat(input.phaseBlendYears()).isEqualTo(1);
        assertThat(input.cashReserveYears()).isEqualTo(2);
        assertThat(input.cashReturnRate()).isEqualByComparingTo("0.04");
        assertThat(input.terminalBalanceTarget()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(input.phases()).isEmpty();
    }

    @Test
    void optimize_nullScenarioFields_fallBackToHardcodedDefaults() {
        var scenarioSansDefaults = new ProjectionScenarioEntity(
                tenant, "Empty", LocalDate.of(2030, 1, 1), null, null, null);
        var input = captureOptimizationInput(buildRequest(req -> req), scenarioSansDefaults);
        assertThat(input.endAge()).isEqualTo(90);
        assertThat(input.inflationRate()).isEqualByComparingTo(BigDecimal.ZERO);
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
        entity.setReturnStddev(new BigDecimal("0.15"));
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

        var hashA = GuardrailProfileService.computeScenarioHash(scenarioWithAccount);
        var hashB = GuardrailProfileService.computeScenarioHash(scenarioWithDifferentAccount);

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

        var hash = GuardrailProfileService.computeScenarioHash(scenarioBadJson);

        assertThat(hash).isNotBlank();
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
                new BigDecimal("0.10"), new BigDecimal("0.15"),
                5000, new BigDecimal("0.95"),
                List.of(), List.of(),
                new BigDecimal("250000"), new BigDecimal("0.05"),
                new BigDecimal("100000"),
                false, OffsetDateTime.now(), OffsetDateTime.now(),
                BigDecimal.ZERO, null, 0, null, 2, new BigDecimal("0.04"), null);
    }

    private GuardrailProfileResponse withSchedule(GuardrailProfileResponse base,
            com.wealthview.core.projection.dto.RothConversionScheduleResponse schedule) {
        return new GuardrailProfileResponse(
                base.id(), base.scenarioId(), base.name(), base.essentialFloor(),
                base.terminalBalanceTarget(), base.returnMean(), base.returnStddev(),
                base.trialCount(), base.confidenceLevel(), base.phases(), base.yearlySpending(),
                base.medianFinalBalance(), base.failureRate(), base.percentile10Final(),
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
        private BigDecimal returnStddev = new BigDecimal("0.15");
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
        private BigDecimal rmdBracketHeadroom;
        private BigDecimal dynamicSequencingBracketRate;

        RequestBuilder(UUID scenarioId) { this.scenarioId = scenarioId; }
        RequestBuilder withConfidence(BigDecimal v) { this.confidence = v; return this; }
        RequestBuilder withRiskTolerance(String v) { this.riskTolerance = v; return this; }
        RequestBuilder withReturnMean(BigDecimal v) { this.returnMean = v; return this; }
        RequestBuilder withReturnStddev(BigDecimal v) { this.returnStddev = v; return this; }
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
                    terminalBalanceTarget, returnMean, returnStddev, trialCount, confidence, phases,
                    portfolioFloor, maxAnnualAdjustmentRate, phaseBlendYears, riskTolerance,
                    cashReserveYears, cashReturnRate, optimizeConversions,
                    conversionBracketRate, rmdTargetBracketRate, buffer,
                    rmdBracketHeadroom, dynamicSequencingBracketRate);
        }
    }
}
