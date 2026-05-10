import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { AxiosInstance } from 'axios';
import { createDashboardApi } from './dashboard';
import type { DashboardSummaryResponse } from './types';

const SAMPLE_SUMMARY: DashboardSummaryResponse = {
    net_worth: '1234567.89',
    total_investments: '890000.00',
    total_cash: '44567.89',
    total_property_equity: '300000.00',
    accounts: [
        { name: 'Fidelity', type: 'brokerage', balance: '456789.00' },
        { name: 'Chase Checking', type: 'bank', balance: '12345.67' },
    ],
    allocation: [
        { category: 'brokerage', value: '456789.00', percentage: '37.00' },
        { category: 'bank', value: '12345.67', percentage: '1.00' },
    ],
};

function makeFakeClient() {
    const get = vi.fn();
    return {
        client: { get } as unknown as AxiosInstance,
        get,
    };
}

describe('createDashboardApi', () => {
    let get: ReturnType<typeof vi.fn>;
    let api: ReturnType<typeof createDashboardApi>;

    beforeEach(() => {
        const f = makeFakeClient();
        get = f.get;
        api = createDashboardApi(f.client);
    });

    describe('getSummary', () => {
        it('GETs /dashboard/summary and returns the body', async () => {
            get.mockResolvedValue({ data: SAMPLE_SUMMARY });

            const summary = await api.getSummary();

            expect(get).toHaveBeenCalledWith('/dashboard/summary');
            expect(summary).toEqual(SAMPLE_SUMMARY);
        });

        it('preserves the response shape verbatim', async () => {
            get.mockResolvedValue({ data: SAMPLE_SUMMARY });

            const summary = await api.getSummary();

            expect(summary.net_worth).toBe('1234567.89');
            expect(summary.accounts).toHaveLength(2);
            expect(summary.allocation[0].category).toBe('brokerage');
        });

        it('propagates network errors to the caller', async () => {
            const err = new Error('network down');
            get.mockRejectedValue(err);

            await expect(api.getSummary()).rejects.toThrow('network down');
        });
    });
});
