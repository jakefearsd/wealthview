export interface InviteCode {
    id: string;
    code: string;
    expires_at: string;
    consumed: boolean;
    is_revoked: boolean;
    used_by_email: string | null;
    created_by_email: string | null;
    created_at: string;
}

export interface TenantUser {
    id: string;
    email: string;
    role: string;
    created_at: string;
}
