import { describe, it, expect } from 'vitest';
import { isTentConfigValid } from './bondTentValidation';
import { DEFAULT_TENT_MIX, DEFAULT_TENT_YIELDS } from './bondTentDefaults';
import type { BondTentConfig } from '../../types/projection';

function config(overrides: Partial<BondTentConfig> = {}): BondTentConfig {
    return {
        coverage_years: 5,
        build_up_start_years: 5,
        mixes: [structuredClone(DEFAULT_TENT_MIX)],
        yields: { ...DEFAULT_TENT_YIELDS },
        ...overrides,
    };
}

describe('isTentConfigValid', () => {
    it('accepts the defaults', () => {
        expect(isTentConfigValid(config())).toBe(true);
    });

    it.each([
        ['coverage below 1', { coverage_years: 0.5 }],
        ['coverage above 10', { coverage_years: 10.5 }],
        ['coverage off the half-year grid', { coverage_years: 2.3 }],
        ['build-up below 0', { build_up_start_years: -1 }],
        ['build-up above 40', { build_up_start_years: 41 }],
        ['no mixes', { mixes: [] }],
        ['four mixes', { mixes: Array.from({ length: 4 }, () => structuredClone(DEFAULT_TENT_MIX)) }],
        ['a blank mix name', { mixes: [{ ...DEFAULT_TENT_MIX, name: '  ' }] }],
        ['a 41-character mix name', { mixes: [{ ...DEFAULT_TENT_MIX, name: 'x'.repeat(41) }] }],
        ['shares not summing to 1', { mixes: [{ ...DEFAULT_TENT_MIX, shares: { ...DEFAULT_TENT_MIX.shares, long: 0.1 } }] }],
        ['a negative share', { mixes: [{ ...DEFAULT_TENT_MIX, shares: { ultrashort: -0.2, short: 0.7, intermediate: 0.5, long: 0 } }] }],
        ['a yield above 20%', { yields: { ...DEFAULT_TENT_YIELDS, long: 0.21 } }],
        ['a yield below -2%', { yields: { ...DEFAULT_TENT_YIELDS, short: -0.03 } }],
    ] as const)('rejects %s', (_label, overrides) => {
        expect(isTentConfigValid(config(overrides as Partial<BondTentConfig>))).toBe(false);
    });
});
