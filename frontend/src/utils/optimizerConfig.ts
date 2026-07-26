import type { GuardrailOptimizationRequest, GuardrailPhase, GuardrailProfileResponse } from '../types/projection';

export type RiskTolerance = 'conservative' | 'moderate' | 'aggressive';

/**
 * All configure-form state for the spending optimizer, in DISPLAY units:
 * rate fields suffixed `Pct` hold percentages (5 = 5%), while the bracket
 * rates stay as fractions (0.22) because both the API and the form's
 * `<select>` options use fractional values. {@link fromProfile} and
 * {@link toRequest} own every unit conversion between this shape and the API.
 */
export interface OptimizerConfig {
    name: string;
    essentialFloor: number;
    terminalTarget: number;
    portfolioFloor: number;
    riskTolerance: RiskTolerance;
    /** Max annual spending change, percent (5 = 5%/yr). API: fraction. */
    spendingFlexibilityPct: number;
    phaseBlendYears: number;
    cashReserveYears: number;
    /** Expected cash return, percent (4 = 4%). API: fraction. */
    cashReturnRatePct: number;
    trialCount: number;
    /** Confidence override, percent — null means "use risk tolerance". API: fraction. */
    confidenceLevelPct: number | null;
    optimizeConversions: boolean;
    /** Fraction (0.22), matching the API and the bracket select options. */
    conversionBracketRate: number;
    /** Fraction (0.12), matching the API and the bracket select options. */
    rmdTargetBracketRate: number;
    exhaustionBuffer: number;
    /** RMD bracket headroom, percent (10 = 10%). API: fraction. */
    rmdBracketHeadroomPct: number;
    /** Target tax bracket for dynamic withdrawal sequencing, percent — null means "off". API: fraction. */
    dynSeqBracketRatePct: number | null;
    phases: GuardrailPhase[];
    /**
     * T24: per-profile toggle for the sustainability search gate. Always sent explicitly to the
     * API (never omitted) so the UI's checkbox state is the source of truth, not the server's
     * omitted-defaults-to-true fallback. The response doesn't echo this raw toggle back, but the
     * derived {@code gated_on} carries the equivalent (which gate actually certified the run), so
     * {@link fromProfile} hydrates from it — a deliberately-conservative profile stays opted out
     * on re-run instead of silently resetting to the checked default.
     */
    gateOnAdaptiveRules: boolean;
}

export function defaultOptimizerConfig(): OptimizerConfig {
    return {
        name: 'Optimized Spending Plan',
        essentialFloor: 30000,
        terminalTarget: 0,
        portfolioFloor: 0,
        riskTolerance: 'moderate',
        spendingFlexibilityPct: 5,
        phaseBlendYears: 1,
        cashReserveYears: 2,
        cashReturnRatePct: 4,
        trialCount: 5000,
        confidenceLevelPct: null,
        optimizeConversions: false,
        conversionBracketRate: 0.22,
        rmdTargetBracketRate: 0.12,
        exhaustionBuffer: 5,
        rmdBracketHeadroomPct: 10,
        dynSeqBracketRatePct: null,
        gateOnAdaptiveRules: true,
        phases: [
            { name: 'Early retirement', start_age: 62, end_age: 72, priority_weight: 3, target_spending: 80000 },
            { name: 'Mid retirement', start_age: 73, end_age: 82, priority_weight: 2, target_spending: 60000 },
            { name: 'Late retirement', start_age: 83, end_age: null, priority_weight: 1, target_spending: 45000 },
        ],
    };
}

/**
 * Rebuilds the configure-form state from a stored guardrail profile,
 * converting the profile's fractional rates into display percentages.
 * Fields the profile does not carry keep their form defaults; the
 * confidence override is intentionally NOT restored (risk tolerance
 * drives it unless the user explicitly sets an override).
 */
export function fromProfile(profile: GuardrailProfileResponse): OptimizerConfig {
    const defaults = defaultOptimizerConfig();

    const config: OptimizerConfig = {
        ...defaults,
        name: profile.name,
        essentialFloor: profile.essential_floor,
        terminalTarget: profile.terminal_balance_target,
        trialCount: profile.trial_count,
        cashReserveYears: profile.cash_reserve_years ?? defaults.cashReserveYears,
        cashReturnRatePct: profile.cash_return_rate != null
            ? profile.cash_return_rate * 100
            : defaults.cashReturnRatePct,
        portfolioFloor: profile.portfolio_floor ?? defaults.portfolioFloor,
        spendingFlexibilityPct: profile.max_annual_adjustment_rate != null
            ? profile.max_annual_adjustment_rate * 100
            : defaults.spendingFlexibilityPct,
        phaseBlendYears: profile.phase_blend_years ?? defaults.phaseBlendYears,
        riskTolerance: profile.risk_tolerance
            ? (profile.risk_tolerance as RiskTolerance)
            : defaults.riskTolerance,
        gateOnAdaptiveRules: profile.gated_on === 'with_rules',
        phases: profile.phases.length > 0 ? profile.phases : defaults.phases,
    };

    if (profile.conversion_schedule) {
        config.optimizeConversions = true;
        config.conversionBracketRate = profile.conversion_schedule.conversion_bracket_rate;
        config.rmdTargetBracketRate = profile.conversion_schedule.rmd_target_bracket_rate;
        config.exhaustionBuffer = profile.conversion_schedule.traditional_exhaustion_buffer;
        if (profile.conversion_schedule.rmd_bracket_headroom != null) {
            config.rmdBracketHeadroomPct = Math.round(profile.conversion_schedule.rmd_bracket_headroom * 100);
        }
    }

    return config;
}

/**
 * Builds the optimization API request from the configure-form state,
 * converting display percentages back into the API's fractional rates.
 * The confidence override and conversion block are omitted entirely when
 * inactive so the backend applies its own defaults.
 */
export function toRequest(config: OptimizerConfig, scenarioId: string): GuardrailOptimizationRequest {
    return {
        scenario_id: scenarioId,
        name: config.name,
        essential_floor: config.essentialFloor,
        terminal_balance_target: config.terminalTarget,
        trial_count: config.trialCount,
        cash_reserve_years: config.cashReserveYears,
        cash_return_rate: config.cashReturnRatePct / 100,
        phases: config.phases,
        portfolio_floor: config.portfolioFloor,
        max_annual_adjustment_rate: config.spendingFlexibilityPct / 100,
        phase_blend_years: config.phaseBlendYears,
        risk_tolerance: config.riskTolerance,
        gate_on_adaptive_rules: config.gateOnAdaptiveRules,
        ...(config.confidenceLevelPct != null ? { confidence_level: config.confidenceLevelPct / 100 } : {}),
        ...(config.dynSeqBracketRatePct != null
            ? { dynamic_sequencing_bracket_rate: config.dynSeqBracketRatePct / 100 }
            : {}),
        ...(config.optimizeConversions ? {
            optimize_conversions: true,
            conversion_bracket_rate: config.conversionBracketRate,
            rmd_target_bracket_rate: config.rmdTargetBracketRate,
            traditional_exhaustion_buffer: config.exhaustionBuffer,
            rmd_bracket_headroom: config.rmdBracketHeadroomPct / 100,
        } : {}),
    };
}
