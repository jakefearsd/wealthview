import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/accounts', () => ({
    getTheoreticalHistory: vi.fn(),
}));

vi.mock('recharts');

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    formatDate: (v: string) => v,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

import { getTheoreticalHistory } from '../api/accounts';
import TheoreticalPortfolioChart from './TheoreticalPortfolioChart';

const history = {
    account_id: 'acc-1',
    symbols: ['AAPL', 'MSFT'],
    has_money_market_holdings: false,
    data_points: [
        { date: '2026-01-01', balance: 100000 },
        { date: '2026-02-01', balance: 105000 },
    ],
};

describe('TheoreticalPortfolioChart', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('fetches history on mount', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getTheoreticalHistory).mockResolvedValue(history as any);
        render(<TheoreticalPortfolioChart accountId="acc-1" accountType="brokerage" />);
        await waitFor(() => {
            expect(getTheoreticalHistory).toHaveBeenCalled();
        });
    });

    it('renders the chart after data loads', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getTheoreticalHistory).mockResolvedValue(history as any);
        render(<TheoreticalPortfolioChart accountId="acc-1" accountType="brokerage" />);
        expect(await screen.findByTestId('area-chart')).toBeInTheDocument();
    });

    it('annualises the return over the plotted span, not the selected horizon', async () => {
        // The series only covers three years even though the default horizon is two years and a
        // 20-year window could be requested: 100 -> 133.1 over exactly 3 years is 10% a year.
        vi.mocked(getTheoreticalHistory).mockResolvedValue({
            ...history,
            data_points: [
                { date: '2023-01-01', total_value: 100 },
                { date: '2026-01-01', total_value: 133.1 },
            ],
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
        render(<TheoreticalPortfolioChart accountId="acc-1" accountType="brokerage" />);

        expect(await screen.findByText('10.00%')).toBeInTheDocument();
    });

    it('words the subtitle off the first and last plotted dates', async () => {
        vi.mocked(getTheoreticalHistory).mockResolvedValue({
            ...history,
            data_points: [
                { date: '2023-01-01', total_value: 100 },
                { date: '2026-01-01', total_value: 133.1 },
            ],
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
        render(<TheoreticalPortfolioChart accountId="acc-1" accountType="brokerage" />);

        expect(await screen.findByText(/worth from 2023-01-01 to 2026-01-01/)).toBeInTheDocument();
    });

    it('shows the failure with a retry, not a "no price data" message, when the API errors', async () => {
        vi.mocked(getTheoreticalHistory).mockRejectedValueOnce(new Error('boom'));
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getTheoreticalHistory).mockResolvedValue(history as any);
        render(<TheoreticalPortfolioChart accountId="acc-1" accountType="brokerage" />);

        expect(await screen.findByText('boom')).toBeInTheDocument();
        expect(screen.queryByText(/No price data available/)).not.toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: 'Retry' }));

        expect(await screen.findByTestId('area-chart')).toBeInTheDocument();
    });
});
