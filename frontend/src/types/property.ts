export interface Property {
    id: string;
    address: string;
    purchase_price: number;
    purchase_date: string;
    current_value: number;
    mortgage_balance: number;
    equity: number;
    loan_amount: number | null;
    annual_interest_rate: number | null;
    loan_term_months: number | null;
    loan_start_date: string | null;
    has_loan_details: boolean;
    use_computed_balance: boolean;
}

export interface PropertyRequest {
    address: string;
    purchase_price: number;
    purchase_date: string;
    current_value: number;
    mortgage_balance?: number;
    loan_amount?: number;
    annual_interest_rate?: number;
    loan_term_months?: number;
    loan_start_date?: string;
    use_computed_balance?: boolean;
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

export interface PropertyValuation {
    id: string;
    valuation_date: string;
    value: number;
    source: string;
}
