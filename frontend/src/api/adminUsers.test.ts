import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    put: vi.mocked(client.put),
};

import { getAllUsers, resetPassword, setUserActive, type AdminUser } from './adminUsers';

const USER: AdminUser = {
    id: 'u1',
    email: 'jane@example.com',
    role: 'member',
    tenant_id: 't1',
    tenant_name: 'Acme',
    is_active: true,
    created_at: '2026-01-01T00:00:00Z',
};

describe('api/adminUsers', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('getAllUsers GETs the admin users endpoint', async () => {
        mocks.get.mockResolvedValue({ data: [USER] });

        const result = await getAllUsers();

        expect(result).toEqual([USER]);
        expect(mocks.get).toHaveBeenCalledWith('/admin/users');
    });

    it('resetPassword PUTs the new password to the user path', async () => {
        mocks.put.mockResolvedValue({ data: undefined });

        await resetPassword('u1', 'NewPass!23');

        expect(mocks.put).toHaveBeenCalledWith('/admin/users/u1/password', {
            new_password: 'NewPass!23',
        });
    });

    it('setUserActive PUTs the active flag to the user path', async () => {
        mocks.put.mockResolvedValue({ data: undefined });

        await setUserActive('u1', false);

        expect(mocks.put).toHaveBeenCalledWith('/admin/users/u1/active', { active: false });
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('403'));

        await expect(getAllUsers()).rejects.toThrow('403');
    });
});
