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
    accounts: {
        linked_account_id: string | null;
        initial_balance: number;
        annual_contribution: number;
        expected_return: number;
        account_type?: string;
    }[];
}
