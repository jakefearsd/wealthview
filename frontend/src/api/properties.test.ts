import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    del: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
        post: mocks.post,
        put: mocks.put,
        delete: mocks.del,
    },
}));

import {
    listProperties,
    getProperty,
    createProperty,
    updateProperty,
    deleteProperty,
    listPropertyExpenses,
    deletePropertyExpense,
    addPropertyExpense,
    getCashFlow,
    getCashFlowDetail,
    getValuationHistory,
    refreshValuation,
    selectZpid,
    getPropertyAnalytics,
    getDepreciationSchedule,
    getRoiAnalysis,
} from './properties';
import type {
    Property,
    PropertyRequest,
    PropertyExpense,
    PropertyExpenseRequest,
    MonthlyCashFlowEntry,
    MonthlyCashFlowDetailEntry,
    PropertyValuation,
    PropertyAnalyticsResponse,
    ValuationRefreshResponse,
    DepreciationScheduleResponse,
    RoiAnalysisResponse,
} from '../types/property';

// properties.ts is a thin Axios wrapper; large response types use focused
// minimal fixtures cast to the declared shape.
const PROPERTY = { id: 'p1', address: '1 Main St' } as unknown as Property;
const REQUEST: PropertyRequest = {
    address: '1 Main St',
    purchase_price: 300000,
    purchase_date: '2020-01-01',
    current_value: 400000,
};
const EXPENSE: PropertyExpense = {
    id: 'e1',
    date: '2026-01-01',
    amount: 500,
    category: 'maintenance',
    description: null,
    frequency: 'one_time',
};
const EXPENSE_REQUEST: PropertyExpenseRequest = {
    date: '2026-01-01',
    amount: 500,
    category: 'maintenance',
};
const CASHFLOW = [{ month: '2026-01' }] as unknown as MonthlyCashFlowEntry[];
const CASHFLOW_DETAIL = [{ month: '2026-01' }] as unknown as MonthlyCashFlowDetailEntry[];
const VALUATION = [{ id: 'v1' }] as unknown as PropertyValuation[];
const REFRESH = { status: 'ok' } as unknown as ValuationRefreshResponse;
const ANALYTICS = { property_id: 'p1' } as unknown as PropertyAnalyticsResponse;
const SCHEDULE = { property_id: 'p1' } as unknown as DepreciationScheduleResponse;
const ROI = { property_id: 'p1' } as unknown as RoiAnalysisResponse;

describe('api/properties', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
        mocks.put.mockReset();
        mocks.del.mockReset();
    });

    it('listProperties returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [PROPERTY] });

        const result = await listProperties();

        expect(result).toEqual([PROPERTY]);
        expect(mocks.get).toHaveBeenCalledWith('/properties');
    });

    it('getProperty embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: PROPERTY });

        const result = await getProperty('p1');

        expect(result).toEqual(PROPERTY);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1');
    });

    it('createProperty POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: PROPERTY });

        const result = await createProperty(REQUEST);

        expect(result).toEqual(PROPERTY);
        expect(mocks.post).toHaveBeenCalledWith('/properties', REQUEST);
    });

    it('updateProperty PUTs to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: PROPERTY });

        const result = await updateProperty('p1', REQUEST);

        expect(result).toEqual(PROPERTY);
        expect(mocks.put).toHaveBeenCalledWith('/properties/p1', REQUEST);
    });

    it('deleteProperty issues a DELETE on the id-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteProperty('p1');

        expect(mocks.del).toHaveBeenCalledWith('/properties/p1');
    });

    it('listPropertyExpenses GETs the property-scoped expenses', async () => {
        mocks.get.mockResolvedValue({ data: [EXPENSE] });

        const result = await listPropertyExpenses('p1');

        expect(result).toEqual([EXPENSE]);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/expenses');
    });

    it('deletePropertyExpense issues a DELETE on the expense path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deletePropertyExpense('p1', 'e1');

        expect(mocks.del).toHaveBeenCalledWith('/properties/p1/expenses/e1');
    });

    it('addPropertyExpense POSTs the expense request', async () => {
        mocks.post.mockResolvedValue({ data: undefined });

        await addPropertyExpense('p1', EXPENSE_REQUEST);

        expect(mocks.post).toHaveBeenCalledWith('/properties/p1/expenses', EXPENSE_REQUEST);
    });

    it('getCashFlow passes from/to query params', async () => {
        mocks.get.mockResolvedValue({ data: CASHFLOW });

        const result = await getCashFlow('p1', '2026-01', '2026-12');

        expect(result).toEqual(CASHFLOW);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/cashflow', {
            params: { from: '2026-01', to: '2026-12' },
        });
    });

    it('getCashFlowDetail passes from/to query params', async () => {
        mocks.get.mockResolvedValue({ data: CASHFLOW_DETAIL });

        const result = await getCashFlowDetail('p1', '2026-01', '2026-12');

        expect(result).toEqual(CASHFLOW_DETAIL);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/cashflow-detail', {
            params: { from: '2026-01', to: '2026-12' },
        });
    });

    it('getValuationHistory GETs the valuations sub-resource', async () => {
        mocks.get.mockResolvedValue({ data: VALUATION });

        const result = await getValuationHistory('p1');

        expect(result).toEqual(VALUATION);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/valuations');
    });

    it('refreshValuation POSTs to the refresh endpoint', async () => {
        mocks.post.mockResolvedValue({ data: REFRESH });

        const result = await refreshValuation('p1');

        expect(result).toEqual(REFRESH);
        expect(mocks.post).toHaveBeenCalledWith('/properties/p1/valuations/refresh');
    });

    it('selectZpid POSTs the chosen zpid', async () => {
        mocks.post.mockResolvedValue({ data: REFRESH });

        const result = await selectZpid('p1', 'z123');

        expect(result).toEqual(REFRESH);
        expect(mocks.post).toHaveBeenCalledWith('/properties/p1/valuations/select-zpid', {
            zpid: 'z123',
        });
    });

    it('getPropertyAnalytics omits the year param when not given', async () => {
        mocks.get.mockResolvedValue({ data: ANALYTICS });

        const result = await getPropertyAnalytics('p1');

        expect(result).toEqual(ANALYTICS);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/analytics', { params: {} });
    });

    it('getPropertyAnalytics includes the year param when given', async () => {
        mocks.get.mockResolvedValue({ data: ANALYTICS });

        await getPropertyAnalytics('p1', 2025);

        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/analytics', {
            params: { year: 2025 },
        });
    });

    it('getDepreciationSchedule GETs the schedule sub-resource', async () => {
        mocks.get.mockResolvedValue({ data: SCHEDULE });

        const result = await getDepreciationSchedule('p1');

        expect(result).toEqual(SCHEDULE);
        expect(mocks.get).toHaveBeenCalledWith('/properties/p1/depreciation-schedule');
    });

    it('getRoiAnalysis maps camelCase params to the snake_case query', async () => {
        mocks.get.mockResolvedValue({ data: ROI });

        const result = await getRoiAnalysis('p1', 'src1', {
            years: 30,
            investmentReturn: 0.07,
            rentGrowth: 0.03,
            expenseInflation: 0.025,
        });

        expect(result).toEqual(ROI);
        expect(mocks.get).toHaveBeenCalledWith(
            '/properties/p1/income-sources/src1/roi-analysis',
            {
                params: {
                    years: 30,
                    investment_return: 0.07,
                    rent_growth: 0.03,
                    expense_inflation: 0.025,
                },
            }
        );
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('404'));

        await expect(getProperty('missing')).rejects.toThrow('404');
    });
});
