package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.common.Entities;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionScenarioEntity;
import com.wealthview.persistence.repository.GuardrailSpendingProfileRepository;
import com.wealthview.persistence.repository.ProjectionScenarioRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// GodClass: this service is the single orchestration point for the guardrail/Roth optimization
// lifecycle (validate -> build input -> optimize -> persist -> map). The members are highly
// cohesive around that one workflow; splitting them would scatter tightly-coupled steps.
@SuppressWarnings("PMD.GodClass")
@Service
public class GuardrailProfileService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailProfileService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final BigDecimal DEFAULT_RETURN_MEAN = new BigDecimal("0.10");
    private static final int DEFAULT_TRIAL_COUNT = 5000;
    private static final BigDecimal DEFAULT_CONFIDENCE = new BigDecimal("0.95");
    private static final BigDecimal DEFAULT_MAX_ADJUSTMENT_RATE = new BigDecimal("0.05");
    private static final int DEFAULT_PHASE_BLEND_YEARS = 1;
    private static final int DEFAULT_CASH_RESERVE_YEARS = 2;
    // Real-terms projection: the cash-reserve sleeve return is a REAL rate. A ~4% nominal money-market
    // yield deflated at 2.5% inflation is ~1.5% real.
    private static final BigDecimal DEFAULT_CASH_RETURN_RATE = new BigDecimal("0.015");
    // Real-terms default inflation assumption (matches the deterministic engine).
    private static final BigDecimal DEFAULT_INFLATION_RATE = new BigDecimal("0.025");

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
        MDC.put("operation", "guardrail-optimize");
        MDC.put("scenarioId", scenarioId.toString());
        try {
            validateConversionRequest(request);

            var scenario = scenarioRepository.findByTenant_IdAndId(tenantId, scenarioId)
                    .orElseThrow(Entities.notFound("Scenario"));

            var projectionInput = projectionInputBuilder.build(scenario, tenantId);

            var params = ScenarioParams.parseOrEmpty(MAPPER, scenario.getParamsJson());
            int birthYear = params.birthYear() != null
                    ? params.birthYear() : java.time.LocalDate.now().getYear() - 35;
            String filingStatus = params.filingStatus();
            String withdrawalOrder = params.withdrawalOrder() != null
                    ? params.withdrawalOrder() : "taxable_first";

            BigDecimal confidence = resolveConfidence(request);

            var optimizationInput = buildOptimizationInput(scenario, projectionInput, request,
                    birthYear, confidence, filingStatus, withdrawalOrder);

            var optimizerResult = spendingOptimizer.optimize(optimizationInput);

            deleteExistingProfile(scenario, scenarioId);

            // name + essential_floor are NOT NULL; a minimal request may omit both.
            // Default name to the scenario name and floor to the resolved input value.
            String profileName = request.name() != null ? request.name() : scenario.getName();
            var entity = new GuardrailSpendingProfileEntity(
                    scenario.getTenant(), scenario, profileName, optimizationInput.essentialFloor());
            populateGuardrailEntity(entity, scenario, request, optimizationInput, optimizerResult);

            var saved = guardrailRepository.save(entity);

            scenario.setSpendingProfile(null);
            scenario.setGuardrailProfile(saved);
            scenarioRepository.save(scenario);

            log.info("Guardrail profile optimized for scenario {} tenant {}", scenarioId, tenantId);
            return GuardrailProfileResponse.from(saved);
        } finally {
            MDC.remove("operation");
            MDC.remove("scenarioId");
        }
    }

    private void validateConversionRequest(GuardrailOptimizationRequest request) {
        if (!Boolean.TRUE.equals(request.optimizeConversions())) {
            return;
        }
        if (request.rmdTargetBracketRate() != null
                && request.conversionBracketRate() != null
                && request.rmdTargetBracketRate().compareTo(request.conversionBracketRate()) > 0) {
            throw new IllegalArgumentException(
                    "RMD target bracket rate must be ≤ conversion bracket rate");
        }
        int buffer = request.traditionalExhaustionBuffer() != null
                ? request.traditionalExhaustionBuffer() : 5;
        if (buffer < 1 || buffer > 15) {
            throw new IllegalArgumentException(
                    "Traditional exhaustion buffer must be between 1 and 15");
        }
    }

    private void deleteExistingProfile(ProjectionScenarioEntity scenario, UUID scenarioId) {
        guardrailRepository.findByScenario_Id(scenarioId).ifPresent(existing -> {
            scenario.setGuardrailProfile(null);
            guardrailRepository.delete(existing);
            guardrailRepository.flush();
        });
    }

    private void populateGuardrailEntity(GuardrailSpendingProfileEntity entity,
                                          ProjectionScenarioEntity scenario,
                                          GuardrailOptimizationRequest request,
                                          GuardrailOptimizationInput optimizationInput,
                                          GuardrailProfileResponse optimizerResult) {
        entity.setTerminalBalanceTarget(optimizationInput.terminalBalanceTarget());
        entity.setReturnMean(optimizationInput.returnMean());
        entity.setTrialCount(optimizationInput.trialCount());
        entity.setConfidenceLevel(optimizationInput.confidenceLevel());
        entity.setScenarioHash(computeScenarioHash(scenario));

        serializeGuardrailJson(entity, optimizationInput, optimizerResult);

        entity.setConversionBracketRate(request.conversionBracketRate());
        entity.setRmdTargetBracketRate(request.rmdTargetBracketRate());
        entity.setTraditionalExhaustionBuffer(
                request.traditionalExhaustionBuffer() != null
                        ? request.traditionalExhaustionBuffer() : 5);
        entity.setRmdBracketHeadroom(
                request.rmdBracketHeadroom() != null
                        ? request.rmdBracketHeadroom() : new BigDecimal("0.10"));

        entity.setMedianFinalBalance(optimizerResult.medianFinalBalance());
        entity.setFailureRate(optimizerResult.failureRate());
        entity.setPercentile10Final(optimizerResult.percentile10Final());
        entity.setPortfolioFloor(optimizationInput.portfolioFloor() != null
                ? optimizationInput.portfolioFloor() : BigDecimal.ZERO);
        entity.setMaxAnnualAdjustmentRate(optimizationInput.maxAnnualAdjustmentRate() != null
                ? optimizationInput.maxAnnualAdjustmentRate() : DEFAULT_MAX_ADJUSTMENT_RATE);
        entity.setPhaseBlendYears(optimizationInput.phaseBlendYears());
        entity.setCashReserveYears(optimizationInput.cashReserveYears());
        entity.setCashReturnRate(optimizationInput.cashReturnRate());
        entity.setRiskTolerance(request.riskTolerance());
    }

    private void serializeGuardrailJson(GuardrailSpendingProfileEntity entity,
                                         GuardrailOptimizationInput optimizationInput,
                                         GuardrailProfileResponse optimizerResult) {
        try {
            entity.setPhases(MAPPER.writeValueAsString(
                    optimizationInput.phases() != null ? optimizationInput.phases() : List.of()));
            entity.setYearlySpending(MAPPER.writeValueAsString(
                    optimizerResult.yearlySpending() != null ? optimizerResult.yearlySpending() : List.of()));
            if (optimizerResult.conversionSchedule() != null) {
                entity.setConversionSchedule(MAPPER.writeValueAsString(optimizerResult.conversionSchedule()));
            }
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to serialize guardrail data", e);
        }
    }

    @Transactional(readOnly = true)
    public GuardrailProfileResponse getGuardrailProfile(UUID tenantId, UUID scenarioId) {
        var entity = guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Guardrail profile"));
        return GuardrailProfileResponse.from(entity);
    }

    @Transactional
    public void deleteGuardrailProfile(UUID tenantId, UUID scenarioId) {
        var entity = guardrailRepository.findByTenant_IdAndScenario_Id(tenantId, scenarioId)
                .orElseThrow(Entities.notFound("Guardrail profile"));

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
                .orElseThrow(Entities.notFound("Guardrail profile"));

        List<GuardrailPhaseInput> phases;
        try {
            phases = MAPPER.readValue(existing.getPhases(),
                    MAPPER.getTypeFactory().constructCollectionType(List.class, GuardrailPhaseInput.class));
        } catch (JacksonException e) {
            phases = List.of();
        }

        var request = new GuardrailOptimizationRequest(
                scenarioId,
                existing.getName(),
                existing.getEssentialFloor(),
                existing.getTerminalBalanceTarget(),
                existing.getReturnMean(),
                existing.getTrialCount(),
                existing.getConfidenceLevel(),
                phases,
                existing.getPortfolioFloor(),
                existing.getMaxAnnualAdjustmentRate(),
                existing.getPhaseBlendYears(),
                existing.getRiskTolerance(),
                existing.getCashReserveYears(),
                existing.getCashReturnRate(),
                existing.getTraditionalExhaustionBuffer() != null,
                existing.getConversionBracketRate(),
                existing.getRmdTargetBracketRate(),
                existing.getTraditionalExhaustionBuffer(),
                existing.getRmdBracketHeadroom(),
                null);

        return optimize(tenantId, scenarioId, request);
    }

    public static String computeScenarioHash(ProjectionScenarioEntity scenario) {
        var sb = new StringBuilder();
        sb.append(scenario.getRetirementDate())
                .append('|').append(scenario.getEndAge())
                .append('|').append(scenario.getInflationRate());

        // Only birth_year from paramsJson affects guardrail optimization
        var hashParams = ScenarioParams.parseOrEmpty(MAPPER, scenario.getParamsJson());
        if (hashParams.birthYear() != null) {
            sb.append('|').append(hashParams.birthYear());
        }

        for (var acct : scenario.getAccounts()) {
            sb.append('|').append(acct.getAccountType())
                    .append(':').append(acct.getInitialBalance())
                    .append(':').append(acct.getAnnualContribution())
                    .append(':').append(acct.getExpectedReturn());
        }

        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // NPathComplexity: this method maps many independent optional request fields, each guarded
    // by a small null/default check. The path count is multiplicative across those guards but
    // every branch is trivial; collapsing it into helpers would only scatter straight-line mapping.
    @SuppressWarnings("PMD.NPathComplexity")
    private GuardrailOptimizationInput buildOptimizationInput(ProjectionScenarioEntity scenario,
                                                               ProjectionInput projectionInput,
                                                               GuardrailOptimizationRequest request,
                                                               int birthYear,
                                                               BigDecimal confidence,
                                                               String filingStatus,
                                                               String withdrawalOrder) {
        return new GuardrailOptimizationInput(
                scenario.getRetirementDate(),
                birthYear,
                scenario.getEndAge() != null ? scenario.getEndAge() : 90,
                scenario.getInflationRate() != null ? scenario.getInflationRate() : DEFAULT_INFLATION_RATE,
                projectionInput.accounts(),
                projectionInput.incomeSources(),
                request.essentialFloor() != null ? request.essentialFloor() : BigDecimal.ZERO,
                request.terminalBalanceTarget() != null ? request.terminalBalanceTarget() : BigDecimal.ZERO,
                request.returnMean() != null ? request.returnMean() : DEFAULT_RETURN_MEAN,
                request.trialCount() != null ? request.trialCount() : DEFAULT_TRIAL_COUNT,
                confidence,
                request.phases() != null ? request.phases() : List.of(),
                null,
                request.portfolioFloor() != null ? request.portfolioFloor() : BigDecimal.ZERO,
                request.maxAnnualAdjustmentRate() != null
                        ? request.maxAnnualAdjustmentRate() : DEFAULT_MAX_ADJUSTMENT_RATE,
                request.phaseBlendYears() != null
                        ? request.phaseBlendYears() : DEFAULT_PHASE_BLEND_YEARS,
                request.cashReserveYears() != null
                        ? request.cashReserveYears() : DEFAULT_CASH_RESERVE_YEARS,
                request.cashReturnRate() != null
                        ? request.cashReturnRate() : DEFAULT_CASH_RETURN_RATE,
                filingStatus,
                withdrawalOrder,
                request.optimizeConversions() != null && request.optimizeConversions(),
                request.conversionBracketRate(),
                request.rmdTargetBracketRate(),
                request.traditionalExhaustionBuffer() != null
                        ? request.traditionalExhaustionBuffer() : 5,
                request.rmdBracketHeadroom() != null
                        ? request.rmdBracketHeadroom() : new BigDecimal("0.10"),
                request.dynamicSequencingBracketRate()
        );
    }

    private BigDecimal resolveConfidence(GuardrailOptimizationRequest request) {
        if (request.confidenceLevel() != null) {
            return request.confidenceLevel();
        }
        if (request.riskTolerance() != null) {
            return switch (request.riskTolerance()) {
                case "conservative" -> new BigDecimal("0.85");
                case "moderate" -> new BigDecimal("0.70");
                case "aggressive" -> new BigDecimal("0.60");
                default -> DEFAULT_CONFIDENCE;
            };
        }
        return DEFAULT_CONFIDENCE;
    }

}
