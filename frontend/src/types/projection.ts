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
}

export interface ProjectionResult {
    scenario_id: string;
    yearly_data: ProjectionYear[];
    final_balance: number;
    years_in_retirement: number;
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
    spending_profile_id?: string | null;
    accounts: ScenarioAccountInput[];
}

export interface UpdateScenarioRequest extends CreateScenarioRequest {}

export interface IncomeStream {
    name: string;
    annual_amount: number;
    start_age: number;
    end_age: number | null;
}

export interface SpendingProfile {
    id: string;
    name: string;
    essential_expenses: number;
    discretionary_expenses: number;
    income_streams: IncomeStream[];
    created_at: string;
    updated_at: string;
}

export interface CreateSpendingProfileRequest {
    name: string;
    essential_expenses: number;
    discretionary_expenses: number;
    income_streams: IncomeStream[];
}

export interface UpdateSpendingProfileRequest extends CreateSpendingProfileRequest {}
