export interface PortfolioDataPoint {
    date: string;
    total_value: number;
}

export interface PortfolioHistory {
    account_id: string;
    data_points: PortfolioDataPoint[];
    symbols: string[];
    weeks: number;
}
