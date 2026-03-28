export interface Account {
    id: string;
    name: string;
    type: string;
    institution: string | null;
    currency: string;
    balance: number;
    created_at: string;
}

export interface AccountRequest {
    name: string;
    type: string;
    institution?: string;
    currency?: string;
}
