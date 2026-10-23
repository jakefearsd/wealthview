import type { BondMaturityValues, BondTentConfig, BondTentMix } from '../../types/projection';

export const MATURITIES: ReadonlyArray<{ key: keyof BondMaturityValues; label: string }> = [
    { key: 'ultrashort', label: 'Ultrashort' },
    { key: 'short', label: 'Short' },
    { key: 'intermediate', label: 'Intermediate' },
    { key: 'long', label: 'Long' },
];

export const MAX_MIXES = 3;
export const MAX_NAME_LENGTH = 40;
export const SHARE_TOLERANCE = 1e-6;

export function shareTotal(mix: BondTentMix): number {
    return MATURITIES.reduce((sum, m) => sum + mix.shares[m.key], 0);
}

/** Mirrors the backend's validation, so the form never sends a tent the server will reject. */
export function isTentConfigValid(config: BondTentConfig): boolean {
    return config.coverage_years >= 1 && config.coverage_years <= 10
        && Number.isInteger(config.coverage_years * 2)
        && config.build_up_start_years >= 0 && config.build_up_start_years <= 40
        && config.mixes.length >= 1 && config.mixes.length <= MAX_MIXES
        && config.mixes.every(mix => mix.name.trim() !== '' && mix.name.length <= MAX_NAME_LENGTH
            && MATURITIES.every(m => mix.shares[m.key] >= 0 && mix.shares[m.key] <= 1)
            && Math.abs(shareTotal(mix) - 1) <= SHARE_TOLERANCE)
        && MATURITIES.every(m => config.yields[m.key] >= -0.02 && config.yields[m.key] <= 0.2);
}
