import { describe, it, expect, vi, beforeEach } from 'vitest';

import client from './client';

vi.mock('./client');

const mocks = {
    get: vi.mocked(client.get),
    post: vi.mocked(client.post),
    put: vi.mocked(client.put),
};

import { getHolding, listHoldings, createHolding, updateHolding } from './holdings';
import type { Holding, HoldingRequest } from '../types/holding';

const HOLDING: Holding = {
    id: 'h1',
    account_id: 'a1',
    symbol: 'VOO',
    quantity: 5,
    cost_basis: 2000,
    is_manual_override: false,
    is_money_market: false,
    money_market_rate: null,
    as_of_date: '2026-01-01',
    current_price: 450,
    market_value: 2250,
    gain_loss: 250,
};

const REQUEST: HoldingRequest = {
    account_id: 'a1',
    symbol: 'VOO',
    quantity: 5,
    cost_basis: 2000,
};

describe('api/holdings', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('getHolding embeds the id in the path', async () => {
        mocks.get.mockResolvedValue({ data: HOLDING });

        const result = await getHolding('h1');

        expect(result).toEqual(HOLDING);
        expect(mocks.get).toHaveBeenCalledWith('/holdings/h1');
    });

    it('listHoldings hits the account-scoped path', async () => {
        mocks.get.mockResolvedValue({ data: [HOLDING] });

        const result = await listHoldings('a1');

        expect(result).toEqual([HOLDING]);
        expect(mocks.get).toHaveBeenCalledWith('/accounts/a1/holdings');
    });

    it('createHolding POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: HOLDING });

        const result = await createHolding(REQUEST);

        expect(result).toEqual(HOLDING);
        expect(mocks.post).toHaveBeenCalledWith('/holdings', REQUEST);
    });

    it('updateHolding PUTs to the id-scoped path', async () => {
        mocks.put.mockResolvedValue({ data: HOLDING });

        const result = await updateHolding('h1', REQUEST);

        expect(result).toEqual(HOLDING);
        expect(mocks.put).toHaveBeenCalledWith('/holdings/h1', REQUEST);
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('404'));

        await expect(getHolding('missing')).rejects.toThrow('404');
    });
});
