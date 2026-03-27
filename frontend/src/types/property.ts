export interface CostSegAllocation {
    asset_class: string;
    allocation: number;
}

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
    property_type: string;
    annual_appreciation_rate: number | null;
    annual_property_tax: number | null;
    annual_insurance_cost: number | null;
    annual_maintenance_cost: number | null;
    in_service_date: string | null;
    land_value: number | null;
    depreciation_method: string;
    useful_life_years: number;
    cost_seg_allocations: CostSegAllocation[];
    bonus_depreciation_rate: number;
    cost_seg_study_year: number | null;
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
    property_type?: string;
    annual_appreciation_rate?: number;
    annual_property_tax?: number;
    annual_insurance_cost?: number;
    annual_maintenance_cost?: number;
    in_service_date?: string;
    land_value?: number;
    depreciation_method?: string;
    useful_life_years?: number;
    cost_seg_allocations?: CostSegAllocation[];
    bonus_depreciation_rate?: number;
    cost_seg_study_year?: number;
}

export interface PropertyIncomeRequest {
    date: string;
    amount: number;
    category: string;
    description?: string;
    frequency?: string;
}

export interface PropertyExpenseRequest {
    date: string;
    amount: number;
    category: string;
    description?: string;
    frequency?: string;
}

export interface PropertyExpense {
    id: string;
    date: string;
    amount: number;
    category: string;
    description: string | null;
    frequency: string;
}

export interface MonthlyCashFlowEntry {
    month: string;
    total_income: number;
    total_expenses: number;
    net_cash_flow: number;
}

export interface MonthlyCashFlowDetailEntry {
    month: string;
    total_income: number;
    expenses_by_category: Record<string, number>;
    total_expenses: number;
    net_cash_flow: number;
}

export interface PropertyValuation {
    id: string;
    valuation_date: string;
    value: number;
    source: string;
}

export interface MortgageProgress {
    original_loan_amount: number;
    current_balance: number;
    principal_paid: number;
    percent_paid_off: number;
    estimated_payoff_date: string;
    months_remaining: number;
}

export interface EquityGrowthPoint {
    month: string;
    equity: number;
    property_value: number;
    mortgage_balance: number;
}

export interface ZillowSearchResult {
    zpid: string;
    address: string;
    zestimate: number;
}

export interface ValuationRefreshResponse {
    status: 'updated' | 'multiple_matches' | 'no_results';
    value: number | null;
    candidates: ZillowSearchResult[] | null;
}

export interface PropertyAnalyticsResponse {
    property_type: string;
    total_appreciation: number;
    appreciation_percent: number;
    mortgage_progress: MortgageProgress | null;
    equity_growth: EquityGrowthPoint[];
    cap_rate: number | null;
    annual_noi: number | null;
    cash_on_cash_return: number | null;
    annual_net_cash_flow: number | null;
    total_cash_invested: number | null;
}

export interface DepreciationScheduleYear {
    tax_year: number;
    annual_depreciation: number;
    cumulative_taken: number;
    remaining_basis: number;
}

export interface ClassBreakdown {
    asset_class: string;
    life_years: number;
    allocation: number;
    bonus_amount: number;
    annual_straight_line: number;
    straight_line_years: number;
}

export interface DepreciationScheduleResponse {
    depreciation_method: string;
    depreciable_basis: number;
    useful_life_years: number;
    in_service_date: string;
    schedule: DepreciationScheduleYear[];
    bonus_depreciation_rate: number | null;
    cost_seg_allocations: CostSegAllocation[] | null;
    class_breakdowns: ClassBreakdown[] | null;
}

export interface HoldScenarioResult {
    ending_property_value: number;
    ending_mortgage_balance: number;
    cumulative_net_cash_flow: number;
    ending_net_worth: number;
}

export interface SellScenarioResult {
    gross_proceeds: number;
    selling_costs: number;
    depreciation_recapture_tax: number;
    capital_gains_tax: number;
    net_proceeds: number;
    ending_net_worth: number;
}

export interface RoiAnalysisResponse {
    income_source_name: string;
    annual_rent: number;
    comparison_years: number;
    hold: HoldScenarioResult;
    sell: SellScenarioResult;
    advantage: 'hold' | 'sell';
    advantage_amount: number;
}
