export interface Price {
    symbol: string;
    date: string;
    close_price: number;
    source: string;
}

export interface PriceRequest {
    symbol: string;
    date: string;
    close_price: number;
}
