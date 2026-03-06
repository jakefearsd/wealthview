export interface ProjectionAccount {
    id: string;
    linked_account_id: string | null;
    initial_balance: number;
    annual_contribution: number;
    expected_return: number;
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
}

export interface ProjectionResult {
    scenario_id: string;
    yearly_data: ProjectionYear[];
    final_balance: number;
    years_in_retirement: number;
}

export interface CreateScenarioRequest {
    name: string;
    retirement_date: string;
    end_age: number;
    inflation_rate: number;
    birth_year: number | null;
    withdrawal_rate: number | null;
    accounts: {
        linked_account_id: string | null;
        initial_balance: number;
        annual_contribution: number;
        expected_return: number;
    }[];
}
