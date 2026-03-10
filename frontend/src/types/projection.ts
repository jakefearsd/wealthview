export interface ProjectionAccount {
    id: string;
    linked_account_id: string | null;
    initial_balance: number;
    annual_contribution: number;
    expected_return: number;
    account_type: string;
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
    roth_conversion_strategy?: string | null;
    target_bracket_rate?: number | null;
    roth_conversion_start_year?: number | null;
    spending_profile_id?: string | null;
    accounts: ScenarioAccountInput[];
}

export interface UpdateScenarioRequest extends CreateScenarioRequest {}

export interface IncomeStream {
    name: string;
    annual_amount: number;
    start_age: number;
    end_age: number | null;
    inflation_rate?: number;
    one_time?: boolean;
}

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
    income_streams: IncomeStream[];
    spending_tiers: SpendingTier[];
    created_at: string;
    updated_at: string;
}

export interface CreateSpendingProfileRequest {
    name: string;
    essential_expenses: number;
    discretionary_expenses: number;
    income_streams: IncomeStream[];
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
    annual_amount: number;
    start_age: number;
    end_age: number | null;
    inflation_rate: number;
    one_time: boolean;
    tax_treatment: string;
    property_id: string | null;
}
