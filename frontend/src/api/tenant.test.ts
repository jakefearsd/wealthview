import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    post: vi.mocked(client.post),
    put: vi.mocked(client.put),
    del: vi.mocked(client.delete),
};

import {
    generateInviteCode,
    listInviteCodes,
    listUsers,
    updateUserRole,
    deleteUser,
    generateInviteCodeWithExpiry,
    revokeInviteCode,
    deleteUsedCodes,
} from './tenant';
import type { InviteCode, TenantUser } from '../types/tenant';

const INVITE: InviteCode = {
    id: 'i1',
    code: 'ABC123',
    expires_at: '2026-02-01T00:00:00Z',
    consumed: false,
    is_revoked: false,
    used_by_email: null,
    created_by_email: 'admin@example.com',
    created_at: '2026-01-01T00:00:00Z',
};

const USER: TenantUser = {
    id: 'u1',
    email: 'jane@example.com',
    role: 'member',
    created_at: '2026-01-01T00:00:00Z',
};

describe('api/tenant', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('generateInviteCode POSTs to the invite-codes endpoint', async () => {
        mocks.post.mockResolvedValue({ data: INVITE });

        const result = await generateInviteCode();

        expect(result).toEqual(INVITE);
        expect(mocks.post).toHaveBeenCalledWith('/tenant/invite-codes');
    });

    it('listInviteCodes returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [INVITE] });

        const result = await listInviteCodes();

        expect(result).toEqual([INVITE]);
        expect(mocks.get).toHaveBeenCalledWith('/tenant/invite-codes');
    });

    it('listUsers returns the tenant users', async () => {
        mocks.get.mockResolvedValue({ data: [USER] });

        const result = await listUsers();

        expect(result).toEqual([USER]);
        expect(mocks.get).toHaveBeenCalledWith('/tenant/users');
    });

    it('updateUserRole PUTs the role to the user-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: { ...USER, role: 'admin' } });

        const result = await updateUserRole('u1', 'admin');

        expect(result.role).toBe('admin');
        expect(mocks.put).toHaveBeenCalledWith('/tenant/users/u1/role', { role: 'admin' });
    });

    it('deleteUser issues a DELETE on the user path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteUser('u1');

        expect(mocks.del).toHaveBeenCalledWith('/tenant/users/u1');
    });

    it('generateInviteCodeWithExpiry sends the expiry_days body when provided', async () => {
        mocks.post.mockResolvedValue({ data: INVITE });

        await generateInviteCodeWithExpiry(14);

        expect(mocks.post).toHaveBeenCalledWith('/tenant/invite-codes', { expiry_days: 14 });
    });

    it('generateInviteCodeWithExpiry omits the body when expiry is undefined', async () => {
        mocks.post.mockResolvedValue({ data: INVITE });

        await generateInviteCodeWithExpiry();

        expect(mocks.post).toHaveBeenCalledWith('/tenant/invite-codes', undefined);
    });

    it('revokeInviteCode PUTs to the revoke path', async () => {
        mocks.put.mockResolvedValue({ data: undefined });

        await revokeInviteCode('i1');

        expect(mocks.put).toHaveBeenCalledWith('/tenant/invite-codes/i1/revoke');
    });

    it('deleteUsedCodes returns the deleted count', async () => {
        mocks.del.mockResolvedValue({ data: { deleted: 3 } });

        const result = await deleteUsedCodes();

        expect(result).toEqual({ deleted: 3 });
        expect(mocks.del).toHaveBeenCalledWith('/tenant/invite-codes/used');
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('403'));

        await expect(listUsers()).rejects.toThrow('403');
    });
});
