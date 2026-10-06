import client from './client';
import type { TenantDetail } from '../types/admin';
import type { InviteCode } from '../types/tenant';

export async function listTenantDetails(): Promise<TenantDetail[]> {
    const { data } = await client.get<TenantDetail[]>('/admin/tenants/details');
    return data;
}

export async function getTenantDetail(id: string): Promise<TenantDetail> {
    const { data } = await client.get<TenantDetail>(`/admin/tenants/${id}`);
    return data;
}

export async function createTenant(name: string): Promise<TenantDetail> {
    const { data } = await client.post<TenantDetail>('/admin/tenants', { name });
    return data;
}

export async function setTenantActive(id: string, active: boolean): Promise<void> {
    await client.put(`/admin/tenants/${id}/active`, { active });
}

export async function generateTenantInviteCode(tenantId: string, expiryDays?: number): Promise<InviteCode> {
    const body = expiryDays != null ? { expiry_days: expiryDays } : undefined;
    const { data } = await client.post<InviteCode>(`/admin/tenants/${tenantId}/invite-codes`, body);
    return data;
}
