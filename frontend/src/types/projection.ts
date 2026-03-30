export interface ProjectionAccount {
    id: string;
    linked_account_id: string | null;
    initial_balance: number;
    annual_contribution: number;
    expected_return: number;
    account_type: string;
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
}

export interface SpendingFeasibility {
    spending_feasible: boolean;
    first_shortfall_year: number | null;
    first_shortfall_age: number | null;
    sustainable_annual_spending: number;
    required_annual_spending: number;
}

export interface ProjectionResult {
    scenario_id: string;
    yearly_data: ProjectionYear[];
    final_balance: number;
    years_in_retirement: number;
    spending_feasibility: SpendingFeasibility | null;
}

export interface CompareResponse {
    results: ProjectionResult[];
}

export interface ScenarioAccountInput {
    linked_account_id: string | null;
    initial_balance: number;
    annual_contribution: number;
    expected_return: number;
    account_type?: string;
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
    spending_profile_id?: string | null;
    use_guardrail_profile?: boolean | null;
    accounts: ScenarioAccountInput[];
    income_sources?: ScenarioIncomeSourceInput[];
}

export interface UpdateScenarioRequest extends CreateScenarioRequest {}

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

export interface UpdateSpendingProfileRequest extends CreateSpendingProfileRequest {}

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
    portfolio_balance_p55: number | null;
}

export interface GuardrailProfileResponse {
    id: string;
    scenario_id: string;
    name: string;
    essential_floor: number;
    terminal_balance_target: number;
    return_mean: number;
    return_stddev: number;
    trial_count: number;
    confidence_level: number;
    phases: GuardrailPhase[];
    yearly_spending: GuardrailYearlySpending[];
    median_final_balance: number;
    failure_rate: number;
    percentile10_final: number;
    percentile55_final: number;
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
    return_stddev?: number;
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
}
