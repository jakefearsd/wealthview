package com.wealthview.core.projection;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wealthview.core.common.Entities;
import com.wealthview.core.projection.dto.GuardrailOptimizationInput;
import com.wealthview.core.projection.dto.GuardrailOptimizationRequest;
import com.wealthview.core.projection.dto.GuardrailPhaseInput;
import com.wealthview.core.projection.dto.GuardrailProfileResponse;
import com.wealthview.core.projection.dto.ProjectionAccountInput;
import com.wealthview.core.projection.dto.ProjectionIncomeSourceInput;
import com.wealthview.core.projection.dto.ProjectionInput;
import com.wealthview.core.projection.dto.ScenarioParams;
import com.wealthview.persistence.entity.GuardrailSpendingProfileEntity;
import com.wealthview.persistence.entity.ProjectionAccountEntity;
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
            // Audit C7 / T7-M3: same default the deterministic engine applies
            // (DeterministicProjectionEngine.resolveProjectionParams) when the scenario has no
            // explicit reference year, so both engines anchor their calendar-year deflation clocks
            // identically.
            int baseYear = projectionInput.referenceYear() != null
                    ? projectionInput.referenceYear() : java.time.LocalDate.now().getYear();

            BigDecimal confidence = resolveConfidence(request);

            var optimizationInput = buildOptimizationInput(scenario, projectionInput, request,
                    birthYear, confidence, filingStatus, withdrawalOrder, params.dividendYield(),
                    params.feeRate(), baseYear);

            var optimizerResult = spendingOptimizer.optimize(optimizationInput);
            var fixedReturnShare = computeFixedReturnShare(projectionInput.accounts());

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
            // Audit C6: floor-clamp disclosure is not persisted (like fixedReturnShare) -- it is
            // only meaningful for the run that just executed, so thread it straight from the fresh
            // optimizer result rather than re-deriving it from the saved entity.
            return GuardrailProfileResponse.from(saved, fixedReturnShare,
                    optimizerResult.floorReduced(), optimizerResult.originalFloorSuccessProbability());
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
        // Audit C4: persist the RESOLVED effective REAL growth rate the optimizer echoed back
        // (GuardrailProfileResponse.returnMean), not the raw request value — the input's
        // returnMean is null whenever the request omitted it (the normal case; the frontend
        // never sends return_mean), and users reloading the profile should see the rate that
        // was actually used. ZERO fallback only for the degenerate zero-year run, whose
        // emptyResult echoes the raw (possibly null) input against a NOT NULL column.
        entity.setReturnMean(optimizerResult.returnMean() != null
                ? optimizerResult.returnMean() : BigDecimal.ZERO);
        entity.setTrialCount(optimizationInput.trialCount());
        entity.setConfidenceLevel(optimizationInput.confidenceLevel());
        var incomeSourceSignatures = toIncomeSourceSignatures(optimizationInput.incomeSources());
        entity.setScenarioHash(computeScenarioHash(scenario, incomeSourceSignatures));

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
                // Audit C4: the stored return_mean is the RESOLVED REAL rate from the prior run;
                // the request field's contract is NOMINAL (the engine Fisher-converts non-null
                // values), so echoing it back would deflate an already-real rate a second time.
                // Passing null re-derives the rate from the scenario's CURRENT allocation — which
                // also picks up allocation edits, the point of reoptimizing a stale profile. An
                // API-supplied explicit nominal override is therefore not preserved across
                // reoptimize; it must be re-sent on a fresh optimize call.
                null,
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

    /**
     * A3 disclosure: the fraction of total initial balance held in accounts carrying an explicit
     * {@code expected_return} override (fixed nominal return, zero Monte Carlo dispersion —
     * {@code PortfolioReturnResolver.fixed}) rather than an allocation-derived, variance-carrying
     * return. Lets the UI warn e.g. "60% of your portfolio uses a fixed return and shows no
     * market variability." Scale 4, zero when there are no overrides or the accounts have no
     * balance to weight by (an all-zero-balance scenario has no meaningful share to report).
     */
    static BigDecimal computeFixedReturnShare(List<ProjectionAccountInput> accounts) {
        BigDecimal totalBalance = BigDecimal.ZERO;
        BigDecimal overrideBalance = BigDecimal.ZERO;
        for (var account : accounts) {
            BigDecimal balance = account.initialBalance();
            if (balance == null) {
                continue;
            }
            totalBalance = totalBalance.add(balance);
            if (account.expectedReturnOverride().isPresent()) {
                overrideBalance = overrideBalance.add(balance);
            }
        }
        if (totalBalance.signum() <= 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return overrideBalance.divide(totalBalance, 4, RoundingMode.HALF_UP);
    }

    /**
     * A minimal (id, effective-amount) projection of a scenario's linked income sources, used only
     * to feed {@link #scenarioSignature}. Kept independent of {@code ProjectionIncomeSourceInput} so
     * callers with different income-source representations (a fully-resolved
     * {@code ProjectionInput}, or raw {@code ScenarioIncomeSourceEntity} rows) can both produce it
     * without coupling this hash/seed logic to either shape.
     */
    public record IncomeSourceSignature(UUID incomeSourceId, BigDecimal effectiveAmount) {}

    /** Builds {@link IncomeSourceSignature}s from the resolved income sources already carried by
     * a {@link GuardrailOptimizationInput} / {@code ProjectionInput} (id + override-or-base amount). */
    public static List<IncomeSourceSignature> toIncomeSourceSignatures(
            List<ProjectionIncomeSourceInput> incomeSources) {
        return incomeSources.stream()
                .map(s -> new IncomeSourceSignature(s.id(), s.annualAmount()))
                .toList();
    }

    public static String computeScenarioHash(ProjectionScenarioEntity scenario,
                                              List<IncomeSourceSignature> incomeSources) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    scenarioSignature(scenario, incomeSources).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Builds the scenario-identifying signature string shared by the cache-key hash
     * ({@link #computeScenarioHash}) and the deterministic optimizer seed
     * ({@link #deriveSeed}), so both stay in lockstep with the same set of identifying fields.
     *
     * <p>Covers every realism-v2 input that changes Monte Carlo results: retirement date, end age,
     * inflation, birth year, each account's type/balance/contribution/expected-return/allocation/cost
     * basis, the scenario's dividend yield and fee rate, and linked income sources (id + effective
     * amount).
     * Accounts and income sources are sorted by id before hashing — {@code accounts} is an unordered
     * JPA bag ({@code @OrderBy("id")} on the entity keeps normal reads stable too) — so the same
     * scenario always yields the same signature regardless of collection iteration order.
     */
    private static String scenarioSignature(ProjectionScenarioEntity scenario,
                                             List<IncomeSourceSignature> incomeSources) {
        var sb = new StringBuilder();
        sb.append(scenario.getRetirementDate())
                .append('|').append(scenario.getEndAge())
                .append('|').append(scenario.getInflationRate());

        var hashParams = ScenarioParams.parseOrEmpty(MAPPER, scenario.getParamsJson());
        if (hashParams.birthYear() != null) {
            sb.append('|').append(hashParams.birthYear());
        }
        sb.append('|').append(hashParams.dividendYield())
                .append('|').append(hashParams.feeRate());

        scenario.getAccounts().stream()
                .sorted(Comparator.comparing(ProjectionAccountEntity::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(acct -> sb.append('|').append(acct.getAccountType())
                        .append(':').append(acct.getInitialBalance())
                        .append(':').append(acct.getAnnualContribution())
                        .append(':').append(acct.getExpectedReturn())
                        .append(':').append(acct.getCostBasis())
                        .append(':').append(allocationSignature(acct.getAllocation())));

        incomeSources.stream()
                .sorted(Comparator.comparing(IncomeSourceSignature::incomeSourceId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(src -> sb.append('|').append(src.incomeSourceId())
                        .append(':').append(src.effectiveAmount()));

        return sb.toString();
    }

    /** Stable (sorted-key) serialization of an account's asset-allocation override, e.g. {@code
     * "us_stock=0.60,us_bond=0.40"}. Empty/absent allocation (allocation-derived accounts with no
     * explicit override) serializes to the empty string. */
    private static String allocationSignature(@Nullable Map<String, BigDecimal> allocation) {
        if (allocation == null || allocation.isEmpty()) {
            return "";
        }
        return allocation.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
    }

    /**
     * Derives a stable {@code long} RNG seed from the scenario's identifying signature, so an
     * unchanged scenario reproduces the same Monte Carlo optimization run-to-run and a changed
     * one (different accounts, inflation, birth year, etc.) gets a different seed.
     */
    private static long deriveSeed(String scenarioSignature) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(scenarioSignature.getBytes(StandardCharsets.UTF_8));
            long seed = 0L;
            for (int i = 0; i < Long.BYTES; i++) {
                seed = (seed << 8) | (hash[i] & 0xffL);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e); // never happens on a standard JRE
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
                                                               String withdrawalOrder,
                                                               BigDecimal dividendYield,
                                                               BigDecimal feeRate,
                                                               int baseYear) {
        var incomeSourceSignatures = toIncomeSourceSignatures(projectionInput.incomeSources());
        return new GuardrailOptimizationInput(
                scenario.getRetirementDate(),
                birthYear,
                scenario.getEndAge() != null ? scenario.getEndAge() : 90,
                scenario.getInflationRate() != null ? scenario.getInflationRate() : DEFAULT_INFLATION_RATE,
                projectionInput.accounts(),
                projectionInput.incomeSources(),
                request.essentialFloor() != null ? request.essentialFloor() : BigDecimal.ZERO,
                request.terminalBalanceTarget() != null ? request.terminalBalanceTarget() : BigDecimal.ZERO,
                // Audit C4: NO default here — an omitted return_mean flows through as null so the
                // engine derives the scenario's fee-adjusted, allocation-blended REAL return
                // (OptimizationContextBuilder.resolveReturnMean). Pre-filling a nominal constant
                // would make that default branch dead code (the frontend never sends return_mean)
                // and reintroduce the nominal-vs-constant-real-bracket frame mismatch.
                request.returnMean(),
                request.trialCount() != null ? request.trialCount() : DEFAULT_TRIAL_COUNT,
                confidence,
                request.phases() != null ? request.phases() : List.of(),
                deriveSeed(scenarioSignature(scenario, incomeSourceSignatures)),
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
                request.dynamicSequencingBracketRate(),
                dividendYield,
                feeRate,
                baseYear
        );
    }

    /**
     * Resolve the confidence level for guardrail optimization.
     * If an explicit confidenceLevel is provided in the request, it takes precedence.
     * Otherwise, maps risk tolerance to target success probabilities (essential floor funded):
     * - conservative: 0.95 (95% probability)
     * - moderate: 0.90 (90% probability)
     * - aggressive: 0.80 (80% probability)
     * If neither is specified, defaults to 0.95.
     */
    BigDecimal resolveConfidence(GuardrailOptimizationRequest request) {
        if (request.confidenceLevel() != null) {
            return request.confidenceLevel();
        }
        if (request.riskTolerance() != null) {
            return switch (request.riskTolerance()) {
                case "conservative" -> new BigDecimal("0.95");
                case "moderate" -> new BigDecimal("0.90");
                case "aggressive" -> new BigDecimal("0.80");
                default -> DEFAULT_CONFIDENCE;
            };
        }
        return DEFAULT_CONFIDENCE;
    }

}
