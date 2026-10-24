import { describe, it, expect } from 'vitest';
import { isTentConfigValid, mixError, tentConfigError } from './bondTentValidation';
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
        ['a fractional build-up', { build_up_start_years: 2.5 }],
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

describe('tentConfigError', () => {
    it('is null for the defaults', () => {
        expect(tentConfigError(config())).toBeNull();
    });

    it('names the mix whose shares are wrong, using the server wording', () => {
        const bad = { ...DEFAULT_TENT_MIX, name: 'Other', shares: { ...DEFAULT_TENT_MIX.shares, long: 0.1 } };

        expect(tentConfigError(config({ mixes: [structuredClone(DEFAULT_TENT_MIX), bad] })))
            .toBe("Bond mix 'Other' shares must each be between 0% and 100% and add up to 100%");
    });

    it('reports a blank mix name', () => {
        expect(mixError({ ...DEFAULT_TENT_MIX, name: ' ' }))
            .toBe('Each bond mix needs a name of at most 40 characters');
    });

    it('rejects repeated mix names, ignoring case and surrounding spaces', () => {
        const second = { ...structuredClone(DEFAULT_TENT_MIX), name: `  ${DEFAULT_TENT_MIX.name.toUpperCase()} ` };

        expect(tentConfigError(config({ mixes: [structuredClone(DEFAULT_TENT_MIX), second] })))
            .toBe('Mix names must be unique');
    });

    it('rejects the reserved No tent name', () => {
        const mixes = [{ ...structuredClone(DEFAULT_TENT_MIX), name: ' no TENT' }];

        expect(tentConfigError(config({ mixes }))).toBe('"No tent" is reserved; choose another mix name');
    });

    it.each([
        [{ coverage_years: 2.3 }, 'Bond tent coverage must be between 1 and 10 years, in half-year steps'],
        [{ build_up_start_years: 41 }, 'Bond tent build-up start must be between 0 and 40 years before retirement'],
        [{ mixes: [] }, 'A bond tent needs one to three bond mixes'],
        [{ yields: { ...DEFAULT_TENT_YIELDS, long: 0.21 } }, 'Bond tent yields must each be between -2% and 20%'],
    ] as const)('explains %j', (overrides, message) => {
        expect(tentConfigError(config(overrides as Partial<BondTentConfig>))).toBe(message);
    });
});
