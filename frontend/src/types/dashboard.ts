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
