import client from './client';

export interface AdminUser {
    id: string;
    email: string;
    role: string;
    tenant_id: string;
    tenant_name: string;
    is_active: boolean;
    created_at: string;
}

export async function getAllUsers(): Promise<AdminUser[]> {
    const { data } = await client.get<AdminUser[]>('/admin/users');
    return data;
}

export async function resetPassword(userId: string, newPassword: string): Promise<void> {
    await client.put(`/admin/users/${userId}/password`, { new_password: newPassword });
}

export async function setUserActive(userId: string, active: boolean): Promise<void> {
    await client.put(`/admin/users/${userId}/active`, { active });
}
