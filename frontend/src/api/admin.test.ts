import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
        post: mocks.post,
        put: mocks.put,
    },
}));

import { listTenantDetails, getTenantDetail, createTenant, setTenantActive } from './admin';
import type { TenantDetail } from '../types/admin';

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
        mocks.get.mockReset();
        mocks.post.mockReset();
        mocks.put.mockReset();
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

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('403'));

        await expect(listTenantDetails()).rejects.toThrow('403');
    });
});
