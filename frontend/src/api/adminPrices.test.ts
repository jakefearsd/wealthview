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

import {
    getPriceStatus,
    syncFinnhub,
    syncYahoo,
    fetchYahoo,
    saveYahooPrices,
    uploadPriceCsv,
    type PriceSyncStatus,
    type FinnhubSyncResult,
    type YahooSyncResult,
    type YahooFetchRequest,
    type PriceEntry,
    type CsvImportResult,
} from './adminPrices';

const STATUS: PriceSyncStatus = {
    symbol: 'AAPL',
    latest_date: '2026-01-02',
    source: 'finnhub',
    stale: false,
};

const FINNHUB: FinnhubSyncResult = { succeeded: 4, total: 5, failures: [] };
const YAHOO: YahooSyncResult = { inserted: 10, updated: 2, failures: [] };
const ENTRY: PriceEntry = { symbol: 'AAPL', date: '2026-01-02', close_price: 195 };
const CSV: CsvImportResult = { imported: 3, errors: [] };

describe('api/adminPrices', () => {
    beforeEach(() => {
        mocks.get.mockReset();
        mocks.post.mockReset();
    });

    it('getPriceStatus GETs the status endpoint', async () => {
        mocks.get.mockResolvedValue({ data: [STATUS] });

        const result = await getPriceStatus();

        expect(result).toEqual([STATUS]);
        expect(mocks.get).toHaveBeenCalledWith('/admin/prices/status');
    });

    it('syncFinnhub POSTs to the sync endpoint', async () => {
        mocks.post.mockResolvedValue({ data: FINNHUB });

        const result = await syncFinnhub();

        expect(result).toEqual(FINNHUB);
        expect(mocks.post).toHaveBeenCalledWith('/admin/prices/sync');
    });

    it('syncYahoo POSTs to the yahoo sync endpoint', async () => {
        mocks.post.mockResolvedValue({ data: YAHOO });

        const result = await syncYahoo();

        expect(result).toEqual(YAHOO);
        expect(mocks.post).toHaveBeenCalledWith('/admin/prices/yahoo/sync');
    });

    it('fetchYahoo POSTs the fetch request body', async () => {
        const request: YahooFetchRequest = {
            symbols: ['AAPL', 'MSFT'],
            from_date: '2026-01-01',
            to_date: '2026-01-31',
        };
        mocks.post.mockResolvedValue({ data: [ENTRY] });

        const result = await fetchYahoo(request);

        expect(result).toEqual([ENTRY]);
        expect(mocks.post).toHaveBeenCalledWith('/admin/prices/yahoo/fetch', request);
    });

    it('saveYahooPrices POSTs the wrapped prices', async () => {
        mocks.post.mockResolvedValue({ data: undefined });

        await saveYahooPrices([ENTRY]);

        expect(mocks.post).toHaveBeenCalledWith('/admin/prices/yahoo/save', {
            prices: [ENTRY],
        });
    });

    it('uploadPriceCsv posts multipart form data with the file', async () => {
        mocks.post.mockResolvedValue({ data: CSV });
        const file = new File(['AAPL,2026-01-02,195'], 'prices.csv', { type: 'text/csv' });

        const result = await uploadPriceCsv(file);

        expect(result).toEqual(CSV);
        const [url, body, config] = mocks.post.mock.calls[0];
        expect(url).toBe('/admin/prices/csv');
        expect(body).toBeInstanceOf(FormData);
        expect((body as FormData).get('file')).toBeInstanceOf(File);
        expect(config.headers['Content-Type']).toBe('multipart/form-data');
    });

    it('propagates server errors', async () => {
        mocks.post.mockRejectedValue(new Error('500'));

        await expect(syncFinnhub()).rejects.toThrow('500');
    });
});
