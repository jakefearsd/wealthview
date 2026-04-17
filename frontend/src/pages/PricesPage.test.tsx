import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/prices', () => ({
    listLatestPrices: vi.fn(),
    createPrice: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: () => ({ role: 'admin' }),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    formatCurrencyInput: (v: string | number) => String(v),
    parseCurrencyInput: (v: string) => v.replace(/,/g, ''),
}));

vi.mock('../utils/styles', () => ({
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { listLatestPrices, createPrice } from '../api/prices';
import PricesPage from './PricesPage';

const aapl = { symbol: 'AAPL', date: '2026-04-10', close_price: 185.50, source: 'finnhub' };

describe('PricesPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.mocked(listLatestPrices).mockResolvedValue([aapl]);
    });

    it('loads and renders latest prices', async () => {
        render(<PricesPage />);
        expect(await screen.findByText('AAPL')).toBeInTheDocument();
        expect(screen.getByText('$185.5')).toBeInTheDocument();
    });

    it('shows add-price form for admins', async () => {
        render(<PricesPage />);
        await screen.findByText('AAPL');
        expect(screen.getByText('Add Manual Price')).toBeInTheDocument();
    });

    it('submits a new price', async () => {
        vi.mocked(createPrice).mockResolvedValue(aapl);
        render(<PricesPage />);
        await screen.findByText('AAPL');

        fireEvent.change(screen.getByPlaceholderText('AAPL'), { target: { value: 'msft' } });
        const dateInput = document.querySelector('input[type="date"]') as HTMLInputElement;
        fireEvent.change(dateInput, { target: { value: '2026-04-12' } });
        fireEvent.change(screen.getByPlaceholderText('185.50'), { target: { value: '405' } });
        fireEvent.click(screen.getByText('Save'));

        await waitFor(() => {
            expect(createPrice).toHaveBeenCalledWith({
                symbol: 'MSFT',
                date: '2026-04-12',
                close_price: 405,
            });
        });
    });

    it('refreshes prices on demand', async () => {
        render(<PricesPage />);
        await screen.findByText('AAPL');

        fireEvent.click(screen.getByText('Refresh'));
        await waitFor(() => {
            expect(listLatestPrices).toHaveBeenCalledTimes(2);
        });
    });

    it('shows error state when loading fails', async () => {
        vi.mocked(listLatestPrices).mockRejectedValueOnce(new Error('boom'));
        render(<PricesPage />);
        expect(await screen.findByText(/Failed to load prices/i)).toBeInTheDocument();
    });
});
