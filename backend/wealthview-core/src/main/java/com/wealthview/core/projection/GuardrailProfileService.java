package com.wealthview.core.projection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wealthview.core.exception.EntityNotFoundException;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class GuardrailProfileService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailProfileService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final BigDecimal DEFAULT_RETURN_MEAN = new BigDecimal("0.10");
    private static final BigDecimal DEFAULT_RETURN_STDDEV = new BigDecimal("0.15");
    private static final int DEFAULT_TRIAL_COUNT = 5000;
    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.95");
    private static final BigDecimal DEFAULT_MAX_ADJUSTMENT_RATE = new BigDecimal("0.05");
    private static final int DEFAULT_PHASE_BLEND_YEARS = 1;

    private final GuardrailSpendingProfileRepository guardrailRepository;
    private final ProjectionScenarioRepository scenarioRepository;
    private final ProjectionInputBuilder projectionInputBuilder;
    private final SpendingOptimizer spendingOptimizer;

    public GuardrailProfileService(GuardrailSpendingProfileRepository guardrailRepository,
                                   ProjectionScenarioRepository scenarioRepository,
                                   ProjectionInputBuilder projectionInputBuilder,
                                   SpendingOptimizer spendingOptimizer) {
        this.guardrailRepository = guardrailRepository;
        this.scenarioRepository = scenarioRepository;
        this.projectionInputBuilder = projectionInputBuilder;
        this.spendingOptimizer = spendingOptimizer;
    }

    @Transactional
    public GuardrailProfileResponse optimize(UUID tenantId, UUID scenarioId,
                                              GuardrailOptimizationRequest request) {
        var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Scenario not found"));

        var projectionInput = projectionInputBuilder.build(scenario, tenantId);

        int birthYear = parseBirthYear(scenario.getParamsJson());

        BigDecimal confidence = resolveConfidence(request);

        var optimizationInput = new GuardrailOptimizationInput(
                scenario.getRetirementDate(),
                birthYear,
                scenario.getEndAge() != null ? scenario.getEndAge() : 90,
                scenario.getInflationRate() != null ? scenario.getInflationRate() : BigDecimal.ZERO,
                projectionInput.accounts(),
                projectionInput.incomeSources(),
                request.essentialFloor(),
                request.terminalBalanceTarget() != null ? request.terminalBalanceTarget() : BigDecimal.ZERO,
                request.returnMean() != null ? request.returnMean() : DEFAULT_RETURN_MEAN,
                request.returnStddev() != null ? request.returnStddev() : DEFAULT_RETURN_STDDEV,
                request.trialCount() != null ? request.trialCount() : DEFAULT_TRIAL_COUNT,
                confidence,
                request.phases() != null ? request.phases() : List.of(),
                null,
                request.portfolioFloor() != null ? request.portfolioFloor() : BigDecimal.ZERO,
                request.maxAnnualAdjustmentRate() != null
                        ? request.maxAnnualAdjustmentRate() : DEFAULT_MAX_ADJUSTMENT_RATE,
                request.phaseBlendYears() != null
                        ? request.phaseBlendYears() : DEFAULT_PHASE_BLEND_YEARS
        );

        var optimizerResult = spendingOptimizer.optimize(optimizationInput);

        guardrailRepository.findByScenario_Id(scenarioId).ifPresent(existing -> {
            scenario.setGuardrailProfile(null);
            guardrailRepository.delete(existing);
            guardrailRepository.flush();
        });

        var entity = new GuardrailSpendingProfileEntity(
                scenario.getTenant(), scenario, request.name(), request.essentialFloor());
        entity.setTerminalBalanceTarget(optimizationInput.terminalBalanceTarget());
        entity.setReturnMean(optimizationInput.returnMean());
        entity.setReturnStddev(optimizationInput.returnStddev());
        entity.setTrialCount(optimizationInput.trialCount());
        entity.setConfidenceLevel(optimizationInput.confidenceLevel());
        entity.setScenarioHash(computeScenarioHash(scenario));

        try {
            entity.setPhases(MAPPER.writeValueAsString(
                    optimizationInput.phases() != null ? optimizationInput.phases() : List.of()));
            entity.setYearlySpending(MAPPER.writeValueAsString(
                    optimizerResult.yearlySpending() != null ? optimizerResult.yearlySpending() : List.of()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize guardrail data", e);
        }

        entity.setMedianFinalBalance(optimizerResult.medianFinalBalance());
        entity.setFailureRate(optimizerResult.failureRate());
        entity.setPercentile10Final(optimizerResult.percentile10Final());
        entity.setPercentile90Final(optimizerResult.percentile90Final());
        entity.setPortfolioFloor(optimizationInput.portfolioFloor() != null
                ? optimizationInput.portfolioFloor() : BigDecimal.ZERO);
        entity.setMaxAnnualAdjustmentRate(optimizationInput.maxAnnualAdjustmentRate() != null
                ? optimizationInput.maxAnnualAdjustmentRate() : DEFAULT_MAX_ADJUSTMENT_RATE);
        entity.setPhaseBlendYears(optimizationInput.phaseBlendYears());
        entity.setRiskTolerance(request.riskTolerance());

        var saved = guardrailRepository.save(entity);

        scenario.setSpendingProfile(null);
        scenario.setGuardrailProfile(saved);
        scenario.setUpdatedAt(OffsetDateTime.now());
        scenarioRepository.save(scenario);

        log.info("Guardrail profile optimized for scenario {} tenant {}", scenarioId, tenantId);
        return GuardrailProfileResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public GuardrailProfileResponse getGuardrailProfile(UUID tenantId, UUID scenarioId) {
        var entity = guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Guardrail profile not found"));
        return GuardrailProfileResponse.from(entity);
    }

    @Transactional
    public void deleteGuardrailProfile(UUID tenantId, UUID scenarioId) {
        var entity = guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Guardrail profile not found"));

        scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId).ifPresent(scenario -> {
            scenario.setGuardrailProfile(null);
            scenarioRepository.save(scenario);
        });

        guardrailRepository.delete(entity);
        log.info("Guardrail profile deleted for scenario {} tenant {}", scenarioId, tenantId);
    }

    @Transactional
    public GuardrailProfileResponse reoptimize(UUID tenantId, UUID scenarioId) {
        var existing = guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId)
                .orElseThrow(() -> new EntityNotFoundException("Guardrail profile not found"));

        List<GuardrailPhaseInput> phases;
        try {
            phases = MAPPER.readValue(existing.getPhases(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, GuardrailPhaseInput.class));
        } catch (JsonProcessingException e) {
            phases = List.of();
        }

        var request = new GuardrailOptimizationRequest(
                scenarioId,
                existing.getName(),
                existing.getEssentialFloor(),
                existing.getTerminalBalanceTarget(),
                existing.getReturnMean(),
                existing.getReturnStddev(),
                existing.getTrialCount(),
                existing.getConfidenceLevel(),
                phases,
                existing.getPortfolioFloor(),
                existing.getMaxAnnualAdjustmentRate(),
                existing.getPhaseBlendYears(),
                existing.getRiskTolerance());

        return optimize(tenantId, scenarioId, request);
    }

    public static String computeScenarioHash(ProjectionScenarioEntity scenario) {
        var sb = new StringBuilder();
        sb.append(scenario.getRetirementDate());
        sb.append('|').append(scenario.getEndAge());
        sb.append('|').append(scenario.getInflationRate());
        sb.append('|').append(scenario.getParamsJson());

        for (var acct : scenario.getAccounts()) {
            sb.append('|').append(acct.getAccountType());
            sb.append(':').append(acct.getInitialBalance());
            sb.append(':').append(acct.getAnnualContribution());
            sb.append(':').append(acct.getExpectedReturn());
        }

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private BigDecimal resolveConfidence(GuardrailOptimizationRequest request) {
        if (request.confidenceLevel() != null) {
            return request.confidenceLevel();
        }
        if (request.riskTolerance() != null) {
            return switch (request.riskTolerance()) {
                case "conservative" -> new BigDecimal("0.90");
                case "moderate" -> new BigDecimal("0.80");
                case "aggressive" -> new BigDecimal("0.70");
                default -> DEFAULT_CONFIDENCE;
            };
        }
        return DEFAULT_CONFIDENCE;
    }

    private int parseBirthYear(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) {
            return java.time.LocalDate.now().getYear() - 35;
        }
        try {
            var node = MAPPER.readTree(paramsJson);
            if (node.has("birth_year")) {
                return node.get("birth_year").asInt();
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse birth_year from paramsJson", e);
        }
        return java.time.LocalDate.now().getYear() - 35;
    }
}
