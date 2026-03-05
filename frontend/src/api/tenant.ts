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
