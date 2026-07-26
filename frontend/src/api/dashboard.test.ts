import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
};

import {
    getDashboardSummary,
    getCombinedPortfolioHistory,
    getSnapshotProjection,
} from './dashboard';
import type {
    DashboardSummary,
    CombinedPortfolioHistory,
    SnapshotProjection,
} from '../types/dashboard';

const SUMMARY: DashboardSummary = {
    net_worth: 500000,
    total_investments: 300000,
    total_cash: 50000,
    total_property_equity: 150000,
    accounts: [],
    allocation: [],
};

const HISTORY: CombinedPortfolioHistory = {
    data_points: [],
    weeks: 104,
    investment_account_count: 2,
    property_count: 1,
};

const SNAPSHOT: SnapshotProjection = {
    data_points: [],
    projection_years: 10,
    investment_account_count: 2,
    property_count: 1,
    portfolio_cagr: 0.07,
};

describe('api/dashboard', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('getDashboardSummary GETs the summary endpoint', async () => {
        mocks.get.mockResolvedValue({ data: SUMMARY });

        const result = await getDashboardSummary();

        expect(result).toEqual(SUMMARY);
        expect(mocks.get).toHaveBeenCalledWith('/dashboard/summary');
    });

    it('getCombinedPortfolioHistory uses the default years param', async () => {
        mocks.get.mockResolvedValue({ data: HISTORY });

        const result = await getCombinedPortfolioHistory();

        expect(result).toEqual(HISTORY);
        expect(mocks.get).toHaveBeenCalledWith('/dashboard/portfolio-history', {
            params: { years: 2 },
        });
    });

    it('getCombinedPortfolioHistory honors a custom years value', async () => {
        mocks.get.mockResolvedValue({ data: HISTORY });

        await getCombinedPortfolioHistory(5);

        expect(mocks.get).toHaveBeenCalledWith('/dashboard/portfolio-history', {
            params: { years: 5 },
        });
    });

    it('getSnapshotProjection uses the default years/lookback params', async () => {
        mocks.get.mockResolvedValue({ data: SNAPSHOT });

        const result = await getSnapshotProjection();

        expect(result).toEqual(SNAPSHOT);
        expect(mocks.get).toHaveBeenCalledWith('/dashboard/snapshot-projection', {
            params: { years: 10, lookback: 10 },
        });
    });

    it('getSnapshotProjection honors custom years/lookback values', async () => {
        mocks.get.mockResolvedValue({ data: SNAPSHOT });

        await getSnapshotProjection(20, 5);

        expect(mocks.get).toHaveBeenCalledWith('/dashboard/snapshot-projection', {
            params: { years: 20, lookback: 5 },
        });
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('500'));

        await expect(getDashboardSummary()).rejects.toThrow('500');
    });
});
