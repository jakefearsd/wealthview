import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
    put: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
        put: mocks.put,
    },
}));

import {
    getSystemStats,
    getLoginActivity,
    getConfig,
    setConfig,
    type SystemStats,
    type LoginActivity,
    type SystemConfig,
} from './adminSystem';

const STATS: SystemStats = {
    total_users: 10,
    active_users: 8,
    total_tenants: 3,
    total_accounts: 20,
    total_holdings: 50,
    total_transactions: 500,
    database_size: '12 MB',
    symbols_tracked: 15,
    stale_symbols: 2,
};

const ACTIVITY: LoginActivity = {
    user_email: 'jane@example.com',
    tenant_id: 't1',
    success: true,
    ip_address: '10.0.0.1',
    created_at: '2026-01-01T00:00:00Z',
};

const CONFIG: SystemConfig = {
    key: 'finnhub_enabled',
    value: 'true',
    updated_at: '2026-01-01T00:00:00Z',
};

describe('api/adminSystem', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.put.mockReset();
    });

    it('getSystemStats GETs the system-stats endpoint', async () => {
        mocks.get.mockResolvedValue({ data: STATS });

        const result = await getSystemStats();

        expect(result).toEqual(STATS);
        expect(mocks.get).toHaveBeenCalledWith('/admin/system-stats');
    });

    it('getLoginActivity uses the default limit param', async () => {
        mocks.get.mockResolvedValue({ data: [ACTIVITY] });

        const result = await getLoginActivity();

        expect(result).toEqual([ACTIVITY]);
        expect(mocks.get).toHaveBeenCalledWith('/admin/login-activity', {
            params: { limit: 50 },
        });
    });

    it('getLoginActivity forwards a custom limit', async () => {
        mocks.get.mockResolvedValue({ data: [] });

        await getLoginActivity(10);

        expect(mocks.get).toHaveBeenCalledWith('/admin/login-activity', {
            params: { limit: 10 },
        });
    });

    it('getConfig GETs the config endpoint', async () => {
        mocks.get.mockResolvedValue({ data: [CONFIG] });

        const result = await getConfig();

        expect(result).toEqual([CONFIG]);
        expect(mocks.get).toHaveBeenCalledWith('/admin/config');
    });

    it('setConfig PUTs the wrapped value to the key path', async () => {
        mocks.put.mockResolvedValue({ data: undefined });

        await setConfig('finnhub_enabled', 'false');

        expect(mocks.put).toHaveBeenCalledWith('/admin/config/finnhub_enabled', {
            value: 'false',
        });
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(getSystemStats()).rejects.toThrow('500');
    });
});
