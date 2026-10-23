import type { BondMaturityValues, BondTentMix } from '../../types/projection';

// FRED monthly averages, September 2026: TB3MS, GS2, GS5, and the midpoint of GS20/GS30. Refresh at release.
export const DEFAULT_TENT_YIELDS: BondMaturityValues = {
    ultrashort: 0.0394,
    short: 0.0465,
    intermediate: 0.048,
    long: 0.0539,
};

export const DEFAULT_TENT_MIX: BondTentMix = {
    name: 'Balanced',
    shares: { ultrashort: 0.2, short: 0.3, intermediate: 0.5, long: 0 },
};

export const DEFAULT_COVERAGE_YEARS = 5;
export const DEFAULT_BUILD_UP_START_YEARS = 5;

export const EXAMPLE_FUNDS: Record<keyof BondMaturityValues, string> = {
    ultrashort: 'SGOV',
    short: 'VGSH',
    intermediate: 'VGIT',
    long: 'TLT',
};
