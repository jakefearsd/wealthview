import type { BondTentConfig, Sex } from '../../types/projection';

/**
 * The slice of a scenario's `params_json` blob the form reads. Every key is optional (and may be null)
 * because the blob is whatever the backend stored, written by older versions or by hand.
 */
export interface ScenarioParamsJson {
    birth_year?: number | null;
    birth_month?: number | null;
    withdrawal_rate?: number | null;
    withdrawal_strategy?: string | null;
    dynamic_ceiling?: number | null;
    dynamic_floor?: number | null;
    filing_status?: string | null;
    other_income?: number | null;
    annual_roth_conversion?: number | null;
    roth_conversion_strategy?: string | null;
    target_bracket_rate?: number | null;
    roth_conversion_start_year?: number | null;
    withdrawal_order?: string | null;
    dynamic_sequencing_bracket_rate?: number | null;
    state?: string | null;
    primary_residence_property_tax?: number | null;
    primary_residence_mortgage_interest?: number | null;
    dividend_yield?: number | null;
    fee_rate?: number | null;
    interest_yield?: number | null;
    include_depression_years?: boolean | null;
    heir_tax_rate?: number | null;
    spouse_birth_year?: number | null;
    spouse_birth_month?: number | null;
    primary_death_age?: number | null;
    spouse_death_age?: number | null;
    survivor_spending_factor?: number | null;
    community_property?: boolean | null;
    stochastic_mortality?: boolean | null;
    primary_sex?: Sex | null;
    spouse_sex?: Sex | null;
    longevity_conditional_age?: number | null;
    bond_tent?: BondTentConfig | null;
}

/**
 * Reads `bond_tent` out of a scenario's `params_json` string; null when absent, empty or malformed
 * (a bad blob must render the form with the tent off, never crash the page).
 */
export function parseBondTent(paramsJson: string | null | undefined): BondTentConfig | null {
    return parseParamsJson(paramsJson).bond_tent ?? null;
}

/**
 * Parses a scenario's `params_json`; an absent, malformed or non-object blob reads as no params.
 * The shape is trusted, not validated: callers fall back with `??` for anything missing.
 */
export function parseParamsJson(paramsJson: string | null | undefined): ScenarioParamsJson {
    if (!paramsJson) return {};
    try {
        const parsed: unknown = JSON.parse(paramsJson);
        return typeof parsed === 'object' && parsed !== null ? (parsed as ScenarioParamsJson) : {};
    } catch {
        return {};
    }
}
