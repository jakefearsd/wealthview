export interface AllocationInput {
    us_stock: number;
    intl_stock: number;
    bond: number;
    cash: number;
}

/** Sub-project B (stochastic mortality): the sex used to select a sex-specific SSA mortality table column. */
export type Sex = 'male' | 'female';

export interface ProjectionAccount {
    id: string;
    linked_account_id: string | null;
    name: string;
    initial_balance: number;
    annual_contribution: number;
    expected_return: number | null;
    account_type: string;
    cost_basis: number | null;
    allocation: AllocationInput | null;
    allocation_is_override: boolean;
    /** Household/survivor modeling: "primary" | "spouse" | "joint" ("joint" only valid for taxable accounts). */
    owner: string;
}

export interface ScenarioIncomeSourceInput {
    income_source_id: string;
    override_annual_amount: number | null;
}

export interface ScenarioIncomeSourceResponse {
    income_source_id: string;
    name: string;
    income_type: string;
    annual_amount: number;
    override_annual_amount: number | null;
    effective_amount: number;
    annual_net_cash_flow?: number | null;
    start_age: number;
    end_age: number | null;
    inflation_rate: number;
    one_time: boolean;
}

export interface GuardrailProfileSummary {
    id: string;
    name: string;
    stale: boolean;
    active: boolean;
}

export interface Scenario {
    id: string;
    name: string;
    retirement_date: string;
    end_age: number;
    inflation_rate: number;
    params_json: string | null;
    accounts: ProjectionAccount[];
    spending_profile: SpendingProfile | null;
    guardrail_profile: GuardrailProfileSummary | null;
    income_sources: ScenarioIncomeSourceResponse[];
    created_at: string;
    updated_at: string;
}

export interface ProjectionYear {
    year: number;
    age: number;
    start_balance: number;
    contributions: number;
    growth: number;
    withdrawals: number;
    end_balance: number;
    retired: boolean;
    traditional_balance: number | null;
    roth_balance: number | null;
    taxable_balance: number | null;
    roth_conversion_amount: number | null;
    tax_liability: number | null;
    essential_expenses: number | null;
    discretionary_expenses: number | null;
    income_streams_total: number | null;
    net_spending_need: number | null;
    spending_surplus: number | null;
    discretionary_after_cuts: number | null;
    rental_income_gross: number | null;
    rental_expenses_total: number | null;
    depreciation_total: number | null;
    rental_loss_applied: number | null;
    suspended_loss_carryforward: number | null;
    social_security_taxable: number | null;
    self_employment_tax: number | null;
    rental_property_details: RentalPropertyYearDetail[] | null;
    income_by_source: Record<string, number> | null;
    property_equity: number | null;
    total_net_worth: number | null;
    surplus_reinvested: number | null;
    taxable_growth: number | null;
    traditional_growth: number | null;
    roth_growth: number | null;
    tax_paid_from_taxable: number | null;
    tax_paid_from_traditional: number | null;
    tax_paid_from_roth: number | null;
    withdrawal_from_taxable: number | null;
    withdrawal_from_traditional: number | null;
    withdrawal_from_roth: number | null;
    federal_tax: number | null;
    state_tax: number | null;
    salt_deduction: number | null;
    used_itemized_deduction: boolean | null;
    irmaa_warning?: boolean;
    rmd_amount: number | null;
    capital_gains_tax: number | null;
    irmaa_surcharge: number | null;
    early_withdrawal_penalty: number | null;
}

export interface SpendingFeasibility {
    spending_feasible: boolean;
    first_shortfall_year: number | null;
    first_shortfall_age: number | null;
    sustainable_annual_spending: number;
    required_annual_spending: number;
}

/**
 * Fields common to BOTH `GET /projections/{id}/run` and `POST /projections/compare` (backend:
 * `ProjectionResultResponse`, wrapped by `ProjectionRunResponse` for `/run` and by
 * `CompareResponse` for `/compare` -- see those two backend types for the wire-level split this
 * mirrors).
 */
export interface ProjectionCompareResult {
    scenario_id: string;
    yearly_data: ProjectionYear[];
    final_balance: number;
    years_in_retirement: number;
    spending_feasibility: SpendingFeasibility | null;
    final_net_worth: number | null;
}

/**
 * `/run`'s response shape: every `ProjectionCompareResult` field plus the two fields ONLY
 * `ProjectionRunResponse` serializes (`unclassified_symbols`, `warnings`) -- `/compare` results
 * never carry them (backend `CompareResponse.results` is `ProjectionResultResponse[]`, which has
 * no such fields at all, not merely null ones). Keeping this name unchanged (rather than
 * introducing e.g. `ProjectionRunResult`) avoids touching every existing single-scenario
 * consumer (`ProjectionDetailPage`, `MilestoneStrip`, `ProjectionCacheContext`) that already
 * relies on it for `/run`.
 */
export interface ProjectionResult extends ProjectionCompareResult {
    unclassified_symbols: string[] | null;
    warnings: string[] | null;
}

export interface CompareResponse {
    results: ProjectionCompareResult[];
}

export interface ScenarioAccountInput {
    linked_account_id: string | null;
    initial_balance: number;
    annual_contribution: number;
    expected_return?: number | null;
    account_type?: string;
    cost_basis?: number | null;
    allocation?: AllocationInput | null;
    /** Household/survivor modeling: "primary" | "spouse" | "joint" ("joint" only valid for taxable accounts). Null/omitted resolves to "primary". */
    owner?: string | null;
}

export interface CreateScenarioRequest {
    name: string;
    retirement_date: string;
    end_age: number;
    inflation_rate: number;
    birth_year: number | null;
    withdrawal_rate: number | null;
    withdrawal_strategy?: string | null;
    dynamic_ceiling?: number | null;
    dynamic_floor?: number | null;
    filing_status?: string | null;
    other_income?: number | null;
    annual_roth_conversion?: number | null;
    withdrawal_order?: string | null;
    dynamic_sequencing_bracket_rate?: number | null;
    roth_conversion_strategy?: string | null;
    target_bracket_rate?: number | null;
    roth_conversion_start_year?: number | null;
    state?: string | null;
    primary_residence_property_tax?: number | null;
    primary_residence_mortgage_interest?: number | null;
    dividend_yield?: number | null;
    fee_rate?: number | null;
    /** Nominal coupon assumption for the bond portion of taxable accounts (decimal, default 0.04, range 0-0.10). */
    interest_yield?: number | null;
    include_depression_years?: boolean | null;
    /** Household/survivor modeling: null means single-person; every field below is then ignored. */
    spouse_birth_year?: number | null;
    /** Primary's assumed death age (50-120). Null resolves to the server's SSA planning default. */
    primary_death_age?: number | null;
    /** Spouse's assumed death age (50-120). Null resolves to the server's SSA planning default. Only meaningful when spouse_birth_year is set. */
    spouse_death_age?: number | null;
    /** Fraction (0.5-1.0) of pre-transition spending the survivor keeps from the first-death year forward. Null resolves to 0.75. Only meaningful when spouse_birth_year is set. */
    survivor_spending_factor?: number | null;
    /** Community-property state: steps up 100% of embedded gain on joint taxable accounts at first death instead of the common-law 50%. Only meaningful when spouse_birth_year is set. */
    community_property?: boolean | null;
    /**
     * Sub-project B: opts the guardrail Monte Carlo optimizer into sampling each spouse's death
     * year per trial from an SSA mortality table, instead of the fixed death ages above (which the
     * deterministic engine and the optimizer's recommendation continue to use unchanged). Null/false
     * ⇒ byte-identical to fixed-death-age modeling. Only meaningful when spouse_birth_year is set.
     */
    stochastic_mortality?: boolean | null;
    /**
     * Sub-project B: the primary's sex, used to select the sex-specific column of the mortality
     * table. Null ⇒ a blended (both-sex) table. Only meaningful when stochastic_mortality is true.
     */
    primary_sex?: Sex | null;
    /** Sub-project B: the spouse's sex, mirroring primary_sex. Requires spouse_birth_year to be set. */
    spouse_sex?: Sex | null;
    /**
     * Sub-project B: age threshold (80-110) for the "at least one spouse still alive at this age"
     * longevity-conditional success metric. Null resolves to 95. Only meaningful when
     * stochastic_mortality is true.
     */
    longevity_conditional_age?: number | null;
    spending_profile_id?: string | null;
    use_guardrail_profile?: boolean | null;
    accounts: ScenarioAccountInput[];
    income_sources?: ScenarioIncomeSourceInput[];
}

export type UpdateScenarioRequest = CreateScenarioRequest;

export interface SpendingTier {
    name: string;
    start_age: number;
    end_age: number | null;
    essential_expenses: number;
    discretionary_expenses: number;
}

export interface SpendingProfile {
    id: string;
    name: string;
    essential_expenses: number;
    discretionary_expenses: number;
    spending_tiers: SpendingTier[];
    created_at: string;
    updated_at: string;
}

export interface CreateSpendingProfileRequest {
    name: string;
    essential_expenses: number;
    discretionary_expenses: number;
    spending_tiers: SpendingTier[];
}

export type UpdateSpendingProfileRequest = CreateSpendingProfileRequest;

export interface ProjectionMonthPoint {
    label: string;
    year: number;
    month: number;
    age: number;
    balance: number;
    traditional_balance: number | null;
    roth_balance: number | null;
    taxable_balance: number | null;
    retired: boolean;
    roth_conversion_amount: number | null;
}

export interface IncomeSource {
    id: string;
    name: string;
    income_type: string;
    annual_amount: number;
    start_age: number;
    end_age: number | null;
    inflation_rate: number;
    one_time: boolean;
    tax_treatment: string;
    property_id: string | null;
    property_address: string | null;
    created_at: string;
    updated_at: string;
    /** Household/survivor modeling: "primary" | "spouse". */
    owner: string;
    /** Fraction (0-1) of this income the survivor keeps after the owner's death. Ignored for social_security sources (statutory keep-larger rule applies instead). */
    survivor_percent: number;
}

export interface CreateIncomeSourceRequest {
    name: string;
    income_type: string;
    annual_amount: number;
    start_age: number;
    end_age: number | null;
    inflation_rate: number;
    one_time: boolean;
    tax_treatment: string;
    property_id: string | null;
    /** Household/survivor modeling: "primary" | "spouse". Null resolves to "primary". */
    owner: string | null;
    /** Fraction (0-1) of this income the survivor keeps after the owner's death. Null resolves to 1.0. Ignored for social_security sources. */
    survivor_percent: number | null;
}

export interface UpdateIncomeSourceRequest {
    name: string;
    income_type: string;
    annual_amount: number;
    start_age: number;
    end_age: number | null;
    inflation_rate: number;
    one_time: boolean;
    tax_treatment: string;
    property_id: string | null;
    /** Household/survivor modeling: "primary" | "spouse". Null resolves to "primary". */
    owner: string | null;
    /** Fraction (0-1) of this income the survivor keeps after the owner's death. Null resolves to 1.0. Ignored for social_security sources. */
    survivor_percent: number | null;
}

export interface GuardrailPhase {
    name: string;
    start_age: number;
    end_age: number | null;
    priority_weight: number;
    target_spending: number | null;
}

export interface GuardrailYearlySpending {
    year: number;
    age: number;
    recommended: number;
    corridor_low: number;
    corridor_high: number;
    essential_floor: number;
    discretionary: number;
    income_offset: number;
    portfolio_withdrawal: number;
    phase_name: string;
    portfolio_balance_median: number | null;
    portfolio_balance_p10: number | null;
    portfolio_balance_p25: number | null;
}

export interface GuardrailProfileResponse {
    id: string;
    scenario_id: string;
    name: string;
    essential_floor: number;
    terminal_balance_target: number;
    /** Resolved REAL (inflation-adjusted) return rate used for the simulation, not nominal. */
    return_mean: number;
    trial_count: number;
    confidence_level: number;
    phases: GuardrailPhase[];
    yearly_spending: GuardrailYearlySpending[];
    median_final_balance: number;
    failure_rate: number;
    success_probability: number;
    /** Success probability if the guardrail spending-cut rule is followed when the portfolio falls behind. */
    success_probability_with_rules: number | null;
    /**
     * T24: which success metric actually certified the recommended schedule — "with_rules" when
     * the profile's gate_on_adaptive_rules toggle was on AND a positive max_annual_adjustment_rate
     * made the rule effective, otherwise "no_adaptation". Always present, never null.
     */
    gated_on: 'no_adaptation' | 'with_rules';
    /** True when the requested essential floor exceeded what the portfolio could sustain and was reduced. Never null. */
    floor_reduced: boolean;
    /** Success probability against the ORIGINAL (pre-reduction) essential floor, only set when floor_reduced. */
    original_floor_success_probability: number | null;
    /** Share of the portfolio (0-1) using a fixed expected return with no simulated market variability. */
    fixed_return_share: number | null;
    percentile10_final: number;
    stale: boolean;
    created_at: string;
    updated_at: string;
    portfolio_floor: number;
    max_annual_adjustment_rate: number;
    phase_blend_years: number;
    risk_tolerance: string | null;
    cash_reserve_years: number;
    cash_return_rate: number;
    conversion_schedule: RothConversionScheduleResponse | null;
    /**
     * Sub-project B (stochastic mortality): the optional longevity-aware summary of one
     * stochastic-mortality Monte Carlo evaluation pass. Present only when the run opted into
     * stochastic_mortality (this app's `ScenarioForm` only exposes the toggle for household
     * scenarios). Null for every toggle-off run and every persisted-profile read that predates
     * this field.
     */
    stochastic_mortality: StochasticMortalityResult | null;
}

/**
 * Sub-project B: the success rate conditional on the household surviving to a given age.
 */
export interface StochasticMortalityLongevityConditional {
    /** The longevity threshold ("at least one spouse alive at this age") the subset was conditioned on. */
    age: number;
    /** The success rate WITHIN the subset of trials whose survivor reached `age`. */
    probability: number;
    /** What share of ALL trials qualified for the subset — how much sample weight `probability` carries. */
    trial_fraction: number;
}

/** Sub-project B: a percentile summary of a sampled death-age distribution, reported as whole years. */
export interface StochasticMortalityAgeDistribution {
    p10: number;
    median: number;
    p90: number;
}

/** Sub-project B: the nested `stochastic_mortality` block on {@link GuardrailProfileResponse}. */
export interface StochasticMortalityResult {
    /** Fraction of trials with essential floors funded every year while either spouse was alive. */
    lifetime_success_probability: number;
    longevity_conditional: StochasticMortalityLongevityConditional;
    first_death_age: StochasticMortalityAgeDistribution;
    second_death_age: StochasticMortalityAgeDistribution;
}

export interface RentalPropertyYearDetail {
    income_source_id: string;
    property_name: string;
    tax_treatment: string;
    gross_rent: number;
    operating_expenses: number;
    mortgage_interest: number;
    property_tax: number;
    depreciation: number;
    net_taxable_income: number;
    loss_applied_to_income: number;
    loss_suspended: number;
    suspended_loss_carryforward: number;
    cash_flow: number;
}

export interface ConversionYearDetail {
    calendar_year: number;
    age: number;
    conversion_amount: number;
    estimated_tax: number;
    traditional_balance_after: number;
    roth_balance_after: number;
    projected_rmd: number;
    other_income: number;
    total_taxable_income: number;
    bracket_used: string;
}

export interface RothConversionScheduleResponse {
    lifetime_tax_with_conversions: number;
    lifetime_tax_without: number;
    tax_savings: number;
    exhaustion_age: number;
    exhaustion_target_met: boolean;
    conversion_bracket_rate: number;
    rmd_target_bracket_rate: number;
    traditional_exhaustion_buffer: number;
    mc_exhaustion_pct: number | null;
    target_traditional_balance: number | null;
    rmd_bracket_headroom: number | null;
    years: ConversionYearDetail[];
}

export interface GuardrailOptimizationRequest {
    scenario_id: string;
    name: string;
    essential_floor: number;
    terminal_balance_target: number;
    return_mean?: number;
    trial_count?: number;
    confidence_level?: number;
    phases: GuardrailPhase[];
    portfolio_floor?: number;
    max_annual_adjustment_rate?: number;
    phase_blend_years?: number;
    risk_tolerance?: 'conservative' | 'moderate' | 'aggressive';
    cash_reserve_years?: number;
    cash_return_rate?: number;
    optimize_conversions?: boolean;
    conversion_bracket_rate?: number;
    rmd_target_bracket_rate?: number;
    traditional_exhaustion_buffer?: number;
    rmd_bracket_headroom?: number;
    dynamic_sequencing_bracket_rate?: number;
    /**
     * T24: per-profile toggle for the sustainability search gate. The UI is the source of truth —
     * always send this explicitly rather than relying on the server's omitted-defaults-to-true
     * behavior, so the checkbox state and the persisted profile never silently diverge.
     */
    gate_on_adaptive_rules: boolean;
}
