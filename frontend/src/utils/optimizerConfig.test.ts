import { describe, it, expect } from 'vitest';
import { defaultOptimizerConfig, fromProfile, toRequest } from './optimizerConfig';
import { makeProfile, makeSchedule } from '../testutil/builders';

describe('defaultOptimizerConfig', () => {
    it('matches the configure-form defaults', () => {
        const config = defaultOptimizerConfig();

        expect(config.name).toBe('Optimized Spending Plan');
        expect(config.essentialFloor).toBe(30000);
        expect(config.terminalTarget).toBe(0);
        expect(config.portfolioFloor).toBe(0);
        expect(config.riskTolerance).toBe('moderate');
        expect(config.spendingFlexibilityPct).toBe(5);
        expect(config.phaseBlendYears).toBe(1);
        expect(config.cashReserveYears).toBe(2);
        expect(config.cashReturnRatePct).toBe(4);
        expect(config.trialCount).toBe(5000);
        expect(config.confidenceLevelPct).toBeNull();
        expect(config.optimizeConversions).toBe(false);
        expect(config.conversionBracketRate).toBe(0.22);
        expect(config.rmdTargetBracketRate).toBe(0.12);
        expect(config.exhaustionBuffer).toBe(5);
        expect(config.rmdBracketHeadroomPct).toBe(10);
        expect(config.dynSeqBracketRatePct).toBeNull();
        expect(config.phases).toHaveLength(3);
    });
});

describe('fromProfile', () => {
    it('restores main parameters and converts stored fractions to display percentages', () => {
        const config = fromProfile(makeProfile());

        expect(config.name).toBe('My Plan');
        expect(config.essentialFloor).toBe(45000);
        expect(config.terminalTarget).toBe(250000);
        expect(config.portfolioFloor).toBe(100000);
        expect(config.trialCount).toBe(2500);
        expect(config.cashReserveYears).toBe(3);
        expect(config.cashReturnRatePct).toBeCloseTo(4.5);
        expect(config.spendingFlexibilityPct).toBeCloseTo(8);
        expect(config.phaseBlendYears).toBe(2);
        expect(config.riskTolerance).toBe('aggressive');
        expect(config.phases).toEqual(makeProfile().phases);
    });

    it('does not restore a confidence override (risk tolerance drives it)', () => {
        const config = fromProfile(makeProfile({ confidence_level: 0.85 }));

        expect(config.confidenceLevelPct).toBeNull();
    });

    it('falls back to defaults for absent nullable fields', () => {
        const config = fromProfile(makeProfile({
            cash_reserve_years: null as unknown as number,
            cash_return_rate: null as unknown as number,
            max_annual_adjustment_rate: null as unknown as number,
            phase_blend_years: null as unknown as number,
            portfolio_floor: null as unknown as number,
            risk_tolerance: null,
            phases: [],
        }));

        expect(config.cashReserveYears).toBe(2);
        expect(config.cashReturnRatePct).toBe(4);
        expect(config.spendingFlexibilityPct).toBe(5);
        expect(config.phaseBlendYears).toBe(1);
        expect(config.portfolioFloor).toBe(0);
        expect(config.riskTolerance).toBe('moderate');
        expect(config.phases).toEqual(defaultOptimizerConfig().phases);
    });

    it('restores Roth conversion inputs when a conversion schedule exists', () => {
        const config = fromProfile(makeProfile({ conversion_schedule: makeSchedule() }));

        expect(config.optimizeConversions).toBe(true);
        expect(config.conversionBracketRate).toBe(0.24);
        expect(config.rmdTargetBracketRate).toBe(0.12);
        expect(config.exhaustionBuffer).toBe(3);
        expect(config.rmdBracketHeadroomPct).toBe(15);
    });

    it('keeps the default headroom when the schedule has none', () => {
        const config = fromProfile(makeProfile({
            conversion_schedule: makeSchedule({ rmd_bracket_headroom: null }),
        }));

        expect(config.optimizeConversions).toBe(true);
        expect(config.rmdBracketHeadroomPct).toBe(10);
    });

    it('leaves conversion optimization off when no schedule exists', () => {
        const config = fromProfile(makeProfile({ conversion_schedule: null }));

        expect(config.optimizeConversions).toBe(false);
        expect(config.conversionBracketRate).toBe(0.22);
    });

    it('hydrates the adaptive gate unchecked from a no_adaptation profile', () => {
        const config = fromProfile(makeProfile({ gated_on: 'no_adaptation' }));

        expect(config.gateOnAdaptiveRules).toBe(false);
    });

    it('hydrates the adaptive gate checked from a with_rules profile', () => {
        const config = fromProfile(makeProfile({ gated_on: 'with_rules' }));

        expect(config.gateOnAdaptiveRules).toBe(true);
    });

    it('does not restore a dynamic-sequencing bracket rate (the profile does not carry one)', () => {
        const config = fromProfile(makeProfile());

        expect(config.dynSeqBracketRatePct).toBeNull();
    });
});

describe('toRequest', () => {
    it('converts display percentages back to fractions', () => {
        const config = { ...defaultOptimizerConfig(), cashReturnRatePct: 4.5, spendingFlexibilityPct: 8 };

        const request = toRequest(config, 's1');

        expect(request.scenario_id).toBe('s1');
        expect(request.cash_return_rate).toBeCloseTo(0.045);
        expect(request.max_annual_adjustment_rate).toBeCloseTo(0.08);
    });

    it('omits confidence_level when no override is set', () => {
        const request = toRequest(defaultOptimizerConfig(), 's1');

        expect('confidence_level' in request).toBe(false);
    });

    it('sends the confidence override as a fraction when set', () => {
        const request = toRequest({ ...defaultOptimizerConfig(), confidenceLevelPct: 85 }, 's1');

        expect(request.confidence_level).toBeCloseTo(0.85);
    });

    it('omits the dynamic-sequencing bracket rate when unset', () => {
        const request = toRequest(defaultOptimizerConfig(), 's1');

        expect('dynamic_sequencing_bracket_rate' in request).toBe(false);
    });

    it('sends the dynamic-sequencing bracket rate as a fraction when set', () => {
        const request = toRequest({ ...defaultOptimizerConfig(), dynSeqBracketRatePct: 22 }, 's1');

        expect(request.dynamic_sequencing_bracket_rate).toBeCloseTo(0.22);
    });

    it('omits conversion fields when conversion optimization is off', () => {
        const request = toRequest(defaultOptimizerConfig(), 's1');

        expect('optimize_conversions' in request).toBe(false);
        expect('conversion_bracket_rate' in request).toBe(false);
        expect('rmd_bracket_headroom' in request).toBe(false);
    });

    it('includes conversion fields with headroom converted to a fraction when on', () => {
        const config = {
            ...defaultOptimizerConfig(),
            optimizeConversions: true,
            conversionBracketRate: 0.24,
            rmdTargetBracketRate: 0.12,
            exhaustionBuffer: 3,
            rmdBracketHeadroomPct: 15,
        };

        const request = toRequest(config, 's1');

        expect(request.optimize_conversions).toBe(true);
        expect(request.conversion_bracket_rate).toBe(0.24);
        expect(request.rmd_target_bracket_rate).toBe(0.12);
        expect(request.traditional_exhaustion_buffer).toBe(3);
        expect(request.rmd_bracket_headroom).toBeCloseTo(0.15);
    });

    it('round-trips a stored profile back to its original fractional values', () => {
        const profile = makeProfile({ conversion_schedule: makeSchedule() });

        const request = toRequest(fromProfile(profile), profile.scenario_id);

        expect(request.name).toBe(profile.name);
        expect(request.essential_floor).toBe(profile.essential_floor);
        expect(request.terminal_balance_target).toBe(profile.terminal_balance_target);
        expect(request.portfolio_floor).toBe(profile.portfolio_floor);
        expect(request.trial_count).toBe(profile.trial_count);
        expect(request.cash_reserve_years).toBe(profile.cash_reserve_years);
        expect(request.cash_return_rate).toBeCloseTo(profile.cash_return_rate);
        expect(request.max_annual_adjustment_rate).toBeCloseTo(profile.max_annual_adjustment_rate);
        expect(request.phase_blend_years).toBe(profile.phase_blend_years);
        expect(request.risk_tolerance).toBe('aggressive');
        expect(request.phases).toEqual(profile.phases);
        expect(request.rmd_bracket_headroom).toBeCloseTo(0.15);
    });
});
