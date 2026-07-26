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
    listExchangeRates,
    createExchangeRate,
    updateExchangeRate,
    deleteExchangeRate,
} from './exchangeRates';
import type { ExchangeRate, ExchangeRateRequest } from '../types/exchangeRate';

const RATE: ExchangeRate = {
    currency_code: 'EUR',
    rate_to_usd: 1.08,
    updated_at: '2026-01-01T00:00:00Z',
};

const REQUEST: ExchangeRateRequest = {
    currency_code: 'EUR',
    rate_to_usd: 1.08,
};

describe('api/exchangeRates', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('listExchangeRates returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [RATE] });

        const result = await listExchangeRates();

        expect(result).toEqual([RATE]);
        expect(mocks.get).toHaveBeenCalledWith('/exchange-rates');
    });

    it('createExchangeRate POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: RATE });

        const result = await createExchangeRate(REQUEST);

        expect(result).toEqual(RATE);
        expect(mocks.post).toHaveBeenCalledWith('/exchange-rates', REQUEST);
    });

    it('updateExchangeRate PUTs to the currency-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: RATE });

        const result = await updateExchangeRate('EUR', REQUEST);

        expect(result).toEqual(RATE);
        expect(mocks.put).toHaveBeenCalledWith('/exchange-rates/EUR', REQUEST);
    });

    it('deleteExchangeRate issues a DELETE on the currency-scoped path', async () => {
        mocks.del.mockResolvedValue({ data: undefined });

        await deleteExchangeRate('EUR');

        expect(mocks.del).toHaveBeenCalledWith('/exchange-rates/EUR');
    });

    it('propagates server errors', async () => {
        mocks.post.mockRejectedValue(new Error('409'));

        await expect(createExchangeRate(REQUEST)).rejects.toThrow('409');
    });
});
