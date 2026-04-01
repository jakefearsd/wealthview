export interface Holding {
    id: string;
    account_id: string;
    symbol: string;
    quantity: number;
    cost_basis: number;
    is_manual_override: boolean;
    is_money_market: boolean;
    money_market_rate: number | null;
    as_of_date: string;
    current_price: number | null;
    market_value: number | null;
    gain_loss: number | null;
}

export interface HoldingRequest {
    account_id: string;
    symbol: string;
    quantity: number;
    cost_basis: number;
}
