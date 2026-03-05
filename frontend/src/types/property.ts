export interface Property {
    id: string;
    address: string;
    purchase_price: number;
    purchase_date: string;
    current_value: number;
    mortgage_balance: number;
    equity: number;
}

export interface PropertyRequest {
    address: string;
    purchase_price: number;
    purchase_date: string;
    current_value: number;
    mortgage_balance?: number;
}

export interface PropertyIncomeRequest {
    date: string;
    amount: number;
    category: string;
    description?: string;
}

export interface PropertyExpenseRequest {
    date: string;
    amount: number;
    category: string;
    description?: string;
}

export interface MonthlyCashFlowEntry {
    month: string;
    total_income: number;
    total_expenses: number;
    net_cash_flow: number;
}
