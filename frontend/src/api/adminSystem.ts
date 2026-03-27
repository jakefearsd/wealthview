import client from './client';

export interface SystemStats {
    total_users: number;
    active_users: number;
    total_tenants: number;
    total_accounts: number;
    total_holdings: number;
    total_transactions: number;
    database_size: string;
    symbols_tracked: number;
    stale_symbols: number;
}

export interface LoginActivity {
    user_email: string;
    tenant_id: string | null;
    success: boolean;
    ip_address: string | null;
    created_at: string;
}

export interface SystemConfig {
    key: string;
    value: string;
    updated_at: string;
}

export async function getSystemStats(): Promise<SystemStats> {
    const { data } = await client.get<SystemStats>('/admin/system-stats');
    return data;
}

export async function getLoginActivity(limit = 50): Promise<LoginActivity[]> {
    const { data } = await client.get<LoginActivity[]>('/admin/login-activity', { params: { limit } });
    return data;
}

export async function getConfig(): Promise<SystemConfig[]> {
    const { data } = await client.get<SystemConfig[]>('/admin/config');
    return data;
}

export async function setConfig(key: string, value: string): Promise<void> {
    await client.put(`/admin/config/${key}`, { value });
}
