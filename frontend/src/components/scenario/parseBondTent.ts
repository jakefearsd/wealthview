import type { BondTentConfig } from '../../types/projection';

/** Reads `bond_tent` out of a scenario's `params_json` string; null when absent or the JSON is empty. */
export function parseBondTent(paramsJson: string | null | undefined): BondTentConfig | null {
    const parsed = paramsJson ? JSON.parse(paramsJson) : {};
    return parsed.bond_tent ?? null;
}
