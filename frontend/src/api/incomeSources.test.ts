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
    listIncomeSources,
    getIncomeSource,
    createIncomeSource,
    updateIncomeSource,
    deleteIncomeSource,
} from './incomeSources';
import type {
    IncomeSource,
    CreateIncomeSourceRequest,
    UpdateIncomeSourceRequest,
} from '../types/projection';

const SOURCE: IncomeSource = {
    id: 'is1',
    name: 'Social Security',
    income_type: 'social_security',
    annual_amount: 30000,
    start_age: 67,
    end_age: null,
    inflation_rate: 0.02,
    one_time: false,
    tax_treatment: 'partially_taxable',
    property_id: null,
    property_address: null,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    owner: 'primary',
    survivor_percent: 1,
};

const CREATE: CreateIncomeSourceRequest = {
    name: 'Social Security',
    income_type: 'social_security',
    annual_amount: 30000,
    start_age: 67,
    end_age: null,
    inflation_rate: 0.02,
    one_time: false,
    tax_treatment: 'partially_taxable',
    property_id: null,
    owner: 'primary',
    survivor_percent: null,
};

const UPDATE: UpdateIncomeSourceRequest = { ...CREATE, annual_amount: 32000 };

describe('api/incomeSources', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
        mocks.put.mockReset();
        mocks.del.mockReset();
    });

    it('listIncomeSources returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [SOURCE] });

        const result = await listIncomeSources();

        expect(result).toEqual([SOURCE]);
        expect(mocks.get).toHaveBeenCalledWith('/income-sources');
    });

    it('getIncomeSource embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: SOURCE });

        const result = await getIncomeSource('is1');

        expect(result).toEqual(SOURCE);
        expect(mocks.get).toHaveBeenCalledWith('/income-sources/is1');
    });

    it('createIncomeSource POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: SOURCE });

        const result = await createIncomeSource(CREATE);

        expect(result).toEqual(SOURCE);
        expect(mocks.post).toHaveBeenCalledWith('/income-sources', CREATE);
    });

    it('updateIncomeSource PUTs to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: { ...SOURCE, annual_amount: 32000 } });

        const result = await updateIncomeSource('is1', UPDATE);

        expect(result.annual_amount).toBe(32000);
        expect(mocks.put).toHaveBeenCalledWith('/income-sources/is1', UPDATE);
    });

    it('deleteIncomeSource issues a DELETE on the id-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteIncomeSource('is1');

        expect(mocks.del).toHaveBeenCalledWith('/income-sources/is1');
    });

    it('propagates server errors', async () => {
        mocks.post.mockRejectedValue(new Error('400'));

        await expect(createIncomeSource(CREATE)).rejects.toThrow('400');
    });
});
