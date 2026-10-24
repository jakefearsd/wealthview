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
const RESERVED_NAME = 'no tent';
export const DUPLICATE_NAME_MESSAGE = 'Mix names must be unique';
export const RESERVED_NAME_MESSAGE = '"No tent" is reserved; choose another mix name';

function normalizedName(mix: BondTentMix): string {
    return mix.name.trim().toLowerCase();
}

/** The name problem across a tent's mixes (reserved or repeated names), or null. */
export function mixNamesError(mixes: BondTentMix[]): string | null {
    const names = mixes.map(normalizedName);
    if (names.includes(RESERVED_NAME)) {
        return RESERVED_NAME_MESSAGE;
    }
    return new Set(names).size === names.length ? null : DUPLICATE_NAME_MESSAGE;
}

export function shareTotal(mix: BondTentMix): number {
    return MATURITIES.reduce((sum, m) => sum + mix.shares[m.key], 0);
}

/** The message for a mix that cannot be saved, or null; wording matches the backend's validation. */
export function mixError(mix: BondTentMix): string | null {
    if (mix.name.trim() === '' || mix.name.length > MAX_NAME_LENGTH) {
        return `Each bond mix needs a name of at most ${MAX_NAME_LENGTH} characters`;
    }
    const sharesOk = MATURITIES.every(m => mix.shares[m.key] >= 0 && mix.shares[m.key] <= 1)
        && Math.abs(shareTotal(mix) - 1) <= SHARE_TOLERANCE;
    return sharesOk
        ? null
        : `Bond mix '${mix.name}' shares must each be between 0% and 100% and add up to 100%`;
}

/** The first problem that would stop the server accepting this tent, or null. Mirrors the backend's validation. */
export function tentConfigError(config: BondTentConfig): string | null {
    if (!(config.coverage_years >= 1 && config.coverage_years <= 10 && Number.isInteger(config.coverage_years * 2))) {
        return 'Bond tent coverage must be between 1 and 10 years, in half-year steps';
    }
    if (!(Number.isInteger(config.build_up_start_years) && config.build_up_start_years >= 0
        && config.build_up_start_years <= 40)) {
        return 'Bond tent build-up start must be between 0 and 40 years before retirement';
    }
    if (config.mixes.length < 1 || config.mixes.length > MAX_MIXES) {
        return 'A bond tent needs one to three bond mixes';
    }
    const mixProblem = config.mixes.map(mixError).find(message => message !== null);
    if (mixProblem) {
        return mixProblem;
    }
    const nameProblem = mixNamesError(config.mixes);
    if (nameProblem) {
        return nameProblem;
    }
    return MATURITIES.every(m => config.yields[m.key] >= -0.02 && config.yields[m.key] <= 0.2)
        ? null
        : 'Bond tent yields must each be between -2% and 20%';
}

export function isTentConfigValid(config: BondTentConfig): boolean {
    return tentConfigError(config) === null;
}
