export interface Account {
    id: string;
    name: string;
    type: string;
    institution: string | null;
    created_at: string;
}

export interface AccountRequest {
    name: string;
    type: string;
    institution?: string;
}
