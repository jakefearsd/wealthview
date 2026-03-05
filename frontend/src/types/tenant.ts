export interface InviteCode {
    id: string;
    code: string;
    expires_at: string;
    consumed: boolean;
    created_at: string;
}

export interface TenantUser {
    id: string;
    email: string;
    role: string;
    created_at: string;
}
