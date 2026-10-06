import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../api/client', () => ({
    default: { get: vi.fn(), delete: vi.fn() },
}));

vi.mock('recharts');

vi.mock('../../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    formatDate: (v: string | null | undefined) => v ?? '--',
    // Local-calendar helper, like the real one: no argument means today.
    todayIso: vi.fn((d?: Date) => (d ? '2025-12-16' : '2026-01-15')),
}));

vi.mock('../../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import client from '../../api/client';
import PriceBrowserTab from './PriceBrowserTab';

describe('PriceBrowserTab', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders the symbol input and search button', () => {
        render(<PriceBrowserTab />);
        expect(screen.getByPlaceholderText('VOO')).toBeInTheDocument();
        expect(screen.getByText('Search')).toBeInTheDocument();
    });

    it('fetches prices for the given symbol on search', async () => {
        vi.mocked(client.get).mockResolvedValue({
            data: [{ symbol: 'AAPL', date: '2026-04-10', close_price: 185.5, source: 'finnhub' }],
        });
        render(<PriceBrowserTab />);

        const symbolInput = screen.getByPlaceholderText('VOO');
        fireEvent.change(symbolInput, { target: { value: 'aapl' } });
        fireEvent.click(screen.getByText('Search'));

        await waitFor(() => {
            expect(client.get).toHaveBeenCalled();
        });
        const url = vi.mocked(client.get).mock.calls[0][0];
        expect(url).toMatch(/AAPL/i);
    });

    it('does nothing if symbol is empty', () => {
        render(<PriceBrowserTab />);
        fireEvent.click(screen.getByText(/Search/i));
        expect(client.get).not.toHaveBeenCalled();
    });

    const voo = [
        { symbol: 'VOO', date: '2026-04-12', close_price: 520, source: 'finnhub' },
        { symbol: 'VOO', date: '2026-04-11', close_price: 515, source: 'finnhub' },
        { symbol: 'VOO', date: '2026-04-10', close_price: 510, source: 'finnhub' },
    ];

    async function searchFor(sym: string, rows = voo) {
        vi.mocked(client.get).mockResolvedValue({ data: rows });
        render(<PriceBrowserTab />);
        fireEvent.change(screen.getByPlaceholderText('VOO'), { target: { value: sym } });
        fireEvent.click(screen.getByText('Search'));
        await screen.findByText('3 prices found');
    }

    it('deletes the price for the symbol that was searched, not whatever is typed now', async () => {
        await searchFor('voo');
        vi.mocked(client.delete).mockResolvedValue({});
        const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);

        fireEvent.change(screen.getByPlaceholderText('VOO'), { target: { value: 'vti' } });
        fireEvent.click(screen.getAllByRole('button', { name: 'Delete' })[0]);

        expect(confirmSpy).toHaveBeenCalledWith('Delete price for VOO on 2026-04-12?');
        await waitFor(() => expect(client.delete).toHaveBeenCalledWith('/admin/prices/VOO/2026-04-12'));
        confirmSpy.mockRestore();
    });

    it('keeps the chart heading on the searched symbol while the input is edited', async () => {
        await searchFor('voo');

        fireEvent.change(screen.getByPlaceholderText('VOO'), { target: { value: 'vti' } });

        expect(screen.getByText('VOO Price History')).toBeInTheDocument();
    });

    it('plots the chart oldest to newest while the table stays newest first', async () => {
        await searchFor('voo');

        const chartDates = JSON.parse(screen.getByTestId('line-chart').getAttribute('data-chart-data') ?? '[]')
            .map((r: { date: string }) => r.date);
        expect(chartDates).toEqual(['2026-04-10', '2026-04-11', '2026-04-12']);
        const tableDates = screen.getAllByRole('row').slice(1).map((r) => r.cells[0].textContent);
        expect(tableDates).toEqual(['2026-04-12', '2026-04-11', '2026-04-10']);
    });

    it('defaults the date range from the local-date helper', () => {
        render(<PriceBrowserTab />);

        const [from, to] = Array.from(document.querySelectorAll('input[type="date"]')) as HTMLInputElement[];
        expect(from.value).toBe('2025-12-16');
        expect(to.value).toBe('2026-01-15');
    });
});
