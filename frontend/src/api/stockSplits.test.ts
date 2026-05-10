import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('./client', () => ({
    default: {
        get: vi.fn(),
        post: vi.fn(),
        delete: vi.fn(),
    },
}));

import client from './client';
import {
    listStockSplits,
    createStockSplit,
    unapplyStockSplit,
    syncStockSplits,
} from './stockSplits';

describe('stockSplits api', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('listStockSplits hits /stock-splits with no params', async () => {
        (client.get as ReturnType<typeof vi.fn>).mockResolvedValue({ data: [] });
        await listStockSplits();
        expect(client.get).toHaveBeenCalledWith('/stock-splits');
    });

    it('listStockSplits encodes optional symbol/from/to', async () => {
        (client.get as ReturnType<typeof vi.fn>).mockResolvedValue({ data: [] });
        await listStockSplits({ symbol: 'AAPL', from: '2020-01-01', to: '2021-01-01' });
        expect(client.get).toHaveBeenCalledWith(
            '/stock-splits?symbol=AAPL&from=2020-01-01&to=2021-01-01'
        );
    });

    it('createStockSplit posts to admin path', async () => {
        (client.post as ReturnType<typeof vi.fn>).mockResolvedValue({
            data: { id: '1', symbol: 'AAPL', effective_date: '2020-08-31', numerator: 4,
                    denominator: 1, source: 'manual', applied_at: '2026-01-01T00:00:00Z', notes: null },
        });
        const result = await createStockSplit({
            symbol: 'AAPL', effective_date: '2020-08-31', numerator: 4, denominator: 1,
        });
        expect(client.post).toHaveBeenCalledWith('/admin/stock-splits', {
            symbol: 'AAPL', effective_date: '2020-08-31', numerator: 4, denominator: 1,
        });
        expect(result.id).toBe('1');
    });

    it('unapplyStockSplit deletes admin/stock-splits/:id', async () => {
        (client.delete as ReturnType<typeof vi.fn>).mockResolvedValue({ data: undefined });
        await unapplyStockSplit('abc');
        expect(client.delete).toHaveBeenCalledWith('/admin/stock-splits/abc');
    });

    it('syncStockSplits posts to admin sync endpoint', async () => {
        (client.post as ReturnType<typeof vi.fn>).mockResolvedValue({
            data: { symbols_scanned: 5, splits_discovered: 1, splits_applied: 1, failed_symbols: [] },
        });
        const r = await syncStockSplits();
        expect(client.post).toHaveBeenCalledWith('/admin/stock-splits/sync');
        expect(r.splits_applied).toBe(1);
    });
});
