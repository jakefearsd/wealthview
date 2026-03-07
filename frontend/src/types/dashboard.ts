export interface DashboardSummary {
    net_worth: number;
    total_investments: number;
    total_cash: number;
    total_property_equity: number;
    accounts: AccountSummary[];
    allocation: AllocationEntry[];
}

export interface AccountSummary {
    name: string;
    type: string;
    balance: number;
}

export interface AllocationEntry {
    category: string;
    value: number;
    percentage: number;
}

export interface CombinedPortfolioDataPoint {
    date: string;
    total_value: number;
    investment_value: number;
    property_equity: number;
}

export interface CombinedPortfolioHistory {
    data_points: CombinedPortfolioDataPoint[];
    weeks: number;
    investment_account_count: number;
    property_count: number;
}
