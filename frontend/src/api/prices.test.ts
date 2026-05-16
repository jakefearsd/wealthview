import { describe, it, expect, vi, beforeEach } from 'vitest';

const mocks = vi.hoisted(() => ({
    get: vi.fn(),
    post: vi.fn(),
}));

vi.mock('./client', () => ({
    default: {
        get: mocks.get,
        post: mocks.post,
    },
}));

import { createPrice, getLatestPrice, listLatestPrices } from './prices';
import type { Price, PriceRequest } from '../types/price';

const PRICE: Price = {
    symbol: 'AAPL',
    date: '2026-01-02',
    close_price: 195.5,
    source: 'manual',
};

const REQUEST: PriceRequest = {
    symbol: 'AAPL',
    date: '2026-01-02',
    close_price: 195.5,
};

describe('api/prices', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
    });

    it('createPrice POSTs the request body', async () => {
        mocks.post.mockResolvedValue({ data: PRICE });

        const result = await createPrice(REQUEST);

        expect(result).toEqual(PRICE);
        expect(mocks.post).toHaveBeenCalledWith('/prices', REQUEST);
    });

    it('getLatestPrice embeds the symbol in the path', async () => {
        mocks.get.mockResolvedValue({ data: PRICE });

        const result = await getLatestPrice('AAPL');

        expect(result).toEqual(PRICE);
        expect(mocks.get).toHaveBeenCalledWith('/prices/AAPL/latest');
    });

    it('listLatestPrices returns the array body', async () => {
        mocks.get.mockResolvedValue({ data: [PRICE] });

        const result = await listLatestPrices();

        expect(result).toEqual([PRICE]);
        expect(mocks.get).toHaveBeenCalledWith('/prices');
    });

    it('propagates server errors', async () => {
        mocks.get.mockRejectedValue(new Error('404'));

        await expect(getLatestPrice('ZZZZ')).rejects.toThrow('404');
    });
});
