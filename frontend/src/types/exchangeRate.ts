export interface ExchangeRate {
    currency_code: string;
    rate_to_usd: number;
    updated_at: string;
}

export interface ExchangeRateRequest {
    currency_code: string;
    rate_to_usd: number;
}
