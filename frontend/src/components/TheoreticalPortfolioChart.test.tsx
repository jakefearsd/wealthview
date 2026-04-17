import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/accounts', () => ({
    getTheoreticalHistory: vi.fn(),
}));

vi.mock('recharts', () => ({
    AreaChart: ({ children }: { children: React.ReactNode }) => <div data-testid="area-chart">{children}</div>,
    Area: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
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
});
