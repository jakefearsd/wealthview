import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    post: vi.mocked(client.post),
    put: vi.mocked(client.put),
};

import { listTenantDetails, getTenantDetail, createTenant, setTenantActive, generateTenantInviteCode } from './admin';
import type { TenantDetail } from '../types/admin';
import type { InviteCode } from '../types/tenant';

const TENANT: TenantDetail = {
    id: 't1',
    name: 'Acme',
    is_active: true,
    user_count: 3,
    account_count: 5,
    created_at: '2026-01-01T00:00:00Z',
};

describe('api/admin', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('listTenantDetails GETs the details endpoint', async () => {
        mocks.get.mockResolvedValue({ data: [TENANT] });

        const result = await listTenantDetails();

        expect(result).toEqual([TENANT]);
        expect(mocks.get).toHaveBeenCalledWith('/admin/tenants/details');
    });

    it('getTenantDetail embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: TENANT });

        const result = await getTenantDetail('t1');

        expect(result).toEqual(TENANT);
        expect(mocks.get).toHaveBeenCalledWith('/admin/tenants/t1');
    });

    it('createTenant POSTs the wrapped name', async () => {
        mocks.post.mockResolvedValue({ data: TENANT });

        const result = await createTenant('Acme');

        expect(result).toEqual(TENANT);
        expect(mocks.post).toHaveBeenCalledWith('/admin/tenants', { name: 'Acme' });
    });

    it('setTenantActive PUTs the active flag to the tenant path', async () => {
        mocks.put.mockResolvedValue({ data: undefined });

        await setTenantActive('t1', false);

        expect(mocks.put).toHaveBeenCalledWith('/admin/tenants/t1/active', { active: false });
    });

    it('generateTenantInviteCode POSTs to the tenant invite path without a body by default', async () => {
        const invite = { id: 'i1', code: 'ABC' } as InviteCode;
        mocks.post.mockResolvedValue({ data: invite });

        const result = await generateTenantInviteCode('t1');

        expect(result).toEqual(invite);
        expect(mocks.post).toHaveBeenCalledWith('/admin/tenants/t1/invite-codes', undefined);
    });

    it('generateTenantInviteCode sends expiry_days when given', async () => {
        mocks.post.mockResolvedValue({ data: {} });

        await generateTenantInviteCode('t1', 30);

        expect(mocks.post).toHaveBeenCalledWith('/admin/tenants/t1/invite-codes', { expiry_days: 30 });
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('403'));

        await expect(listTenantDetails()).rejects.toThrow('403');
    });
});
