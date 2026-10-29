import type { BondTentConfig } from '../../types/projection';

/**
 * Reads `bond_tent` out of a scenario's `params_json` string; null when absent, empty or malformed
 * (a bad blob must render the form with the tent off, never crash the page).
 */
export function parseBondTent(paramsJson: string | null | undefined): BondTentConfig | null {
    return parseParamsJson(paramsJson).bond_tent ?? null;
}

/**
 * Parses a scenario's `params_json`; an absent, malformed or non-object blob reads as no params.
 * The result is deliberately loosely typed (JSON.parse's own return type): callers pick fields out of it.
 */
export function parseParamsJson(paramsJson: string | null | undefined): ReturnType<typeof JSON.parse> {
    if (!paramsJson) return {};
    try {
        const parsed = JSON.parse(paramsJson);
        return typeof parsed === 'object' && parsed !== null ? parsed : {};
    } catch {
        return {};
    }
}
