export interface PortfolioDataPoint {
    date: string;
    total_value: number;
}

export interface PortfolioHistory {
    account_id: string;
    data_points: PortfolioDataPoint[];
    symbols: string[];
    weeks: number;
    has_money_market_holdings: boolean;
    money_market_total: number | null;
}
