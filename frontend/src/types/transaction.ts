export interface Transaction {
    id: string;
    account_id: string;
    date: string;
    type: string;
    symbol: string | null;
    quantity: number | null;
    amount: number;
    created_at: string;
}

export interface TransactionRequest {
    date: string;
    type: string;
    symbol?: string;
    quantity?: number;
    amount: number;
}
