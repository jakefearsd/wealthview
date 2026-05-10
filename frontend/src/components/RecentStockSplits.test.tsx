import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/stockSplits', () => ({
    listStockSplits: vi.fn(),
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
}));

import { listStockSplits } from '../api/stockSplits';
import RecentStockSplits from './RecentStockSplits';

describe('RecentStockSplits', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders the empty state when no splits affect the portfolio', async () => {
        (listStockSplits as ReturnType<typeof vi.fn>).mockResolvedValue([]);

        render(<RecentStockSplits />);

        await waitFor(() => {
            expect(screen.getByText(/No stock splits affect/)).toBeInTheDocument();
        });
    });

    it('lists splits with their symbol, ratio, and source label', async () => {
        (listStockSplits as ReturnType<typeof vi.fn>).mockResolvedValue([
            {
                id: '1', symbol: 'AAPL', effective_date: '2020-08-31',
                numerator: 4, denominator: 1, source: 'finnhub',
                applied_at: '2026-01-01T00:00:00Z', notes: null,
            },
            {
                id: '2', symbol: 'TSLA', effective_date: '2022-08-25',
                numerator: 3, denominator: 1, source: 'manual',
                applied_at: '2026-01-01T00:00:00Z', notes: null,
            },
        ]);

        render(<RecentStockSplits />);

        await waitFor(() => {
            expect(screen.getByText('AAPL')).toBeInTheDocument();
        });
        expect(screen.getByText('TSLA')).toBeInTheDocument();
        expect(screen.getByText('4:1')).toBeInTheDocument();
        expect(screen.getByText('3:1')).toBeInTheDocument();
        expect(screen.getByText('Auto-detected via Finnhub')).toBeInTheDocument();
        expect(screen.getByText('Manually entered')).toBeInTheDocument();
    });

    it('passes through symbol filter to the API call', async () => {
        (listStockSplits as ReturnType<typeof vi.fn>).mockResolvedValue([]);

        render(<RecentStockSplits symbol="AAPL" />);

        await waitFor(() => {
            expect(listStockSplits).toHaveBeenCalledWith({ symbol: 'AAPL' });
        });
    });

    it('uses custom title when provided', async () => {
        (listStockSplits as ReturnType<typeof vi.fn>).mockResolvedValue([]);

        render(<RecentStockSplits title="Split history for AAPL" />);

        expect(screen.getByText('Split history for AAPL')).toBeInTheDocument();
    });
});
