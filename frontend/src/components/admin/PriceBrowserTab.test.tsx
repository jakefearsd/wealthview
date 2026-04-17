import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../../api/client', () => ({
    default: { get: vi.fn() },
}));

vi.mock('recharts', () => ({
    LineChart: ({ children }: { children: React.ReactNode }) => <div data-testid="line-chart">{children}</div>,
    Line: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    CartesianGrid: () => <div />,
}));

vi.mock('../../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
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
});
