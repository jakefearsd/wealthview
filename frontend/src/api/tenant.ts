import client from './client';
import type { InviteCode, TenantUser } from '../types/tenant';

export async function generateInviteCode(): Promise<InviteCode> {
    const { data } = await client.post<InviteCode>('/tenant/invite-codes');
    return data;
}

export async function listInviteCodes(): Promise<InviteCode[]> {
    const { data } = await client.get<InviteCode[]>('/tenant/invite-codes');
    return data;
}

export async function listUsers(): Promise<TenantUser[]> {
    const { data } = await client.get<TenantUser[]>('/tenant/users');
    return data;
}

export async function updateUserRole(userId: string, role: string): Promise<TenantUser> {
    const { data } = await client.put<TenantUser>(`/tenant/users/${userId}/role`, { role });
    return data;
}

export async function deleteUser(userId: string): Promise<void> {
    await client.delete(`/tenant/users/${userId}`);
}

export async function generateInviteCodeWithExpiry(expiryDays?: number): Promise<InviteCode> {
    const body = expiryDays != null ? { expiry_days: expiryDays } : undefined;
    const { data } = await client.post<InviteCode>('/tenant/invite-codes', body);
    return data;
}

export async function revokeInviteCode(id: string): Promise<void> {
    await client.put(`/tenant/invite-codes/${id}/revoke`);
}

export async function deleteUsedCodes(): Promise<{ deleted: number }> {
    const { data } = await client.delete<{ deleted: number }>('/tenant/invite-codes/used');
    return data;
}
