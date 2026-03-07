import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { CombinedPortfolioHistory } from '../types/dashboard';

vi.mock('../api/dashboard', () => ({
    getCombinedPortfolioHistory: vi.fn(),
}));

vi.mock('recharts', () => ({
    AreaChart: ({ data }: { data: unknown[] }) => (
        <div data-testid="area-chart" data-chart-data={JSON.stringify(data)} />
    ),
    Area: () => <div data-testid="area" />,
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

import { getCombinedPortfolioHistory } from '../api/dashboard';
import CombinedPortfolioChart from './CombinedPortfolioChart';

const mockGetHistory = vi.mocked(getCombinedPortfolioHistory);

const mockHistory: CombinedPortfolioHistory = {
    data_points: [
        { date: '2025-01-03', total_value: 150000, investment_value: 100000, property_equity: 50000 },
        { date: '2025-01-10', total_value: 155000, investment_value: 103000, property_equity: 52000 },
        { date: '2025-01-17', total_value: 160000, investment_value: 106000, property_equity: 54000 },
    ],
    weeks: 3,
    investment_account_count: 2,
    property_count: 1,
};

describe('CombinedPortfolioChart', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders loading state initially', () => {
        mockGetHistory.mockReturnValue(new Promise(() => {}));
        render(<CombinedPortfolioChart />);

        expect(screen.getByText('Loading...')).toBeInTheDocument();
    });

    it('renders empty state when no data points', async () => {
        mockGetHistory.mockResolvedValue({
            data_points: [],
            weeks: 0,
            investment_account_count: 0,
            property_count: 0,
        });
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByText('No portfolio history data available.')).toBeInTheDocument();
        });
    });

    it('renders empty state on API error', async () => {
        mockGetHistory.mockRejectedValue(new Error('Network error'));
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByText('No portfolio history data available.')).toBeInTheDocument();
        });
    });

    it('renders chart heading and subtitle with correct counts', async () => {
        mockGetHistory.mockResolvedValue(mockHistory);
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByText('Combined Portfolio History')).toBeInTheDocument();
        });
        expect(screen.getByText('2 investment accounts + 1 property')).toBeInTheDocument();
    });

    it('renders subtitle for investments only when no properties', async () => {
        mockGetHistory.mockResolvedValue({
            ...mockHistory,
            property_count: 0,
            investment_account_count: 1,
        });
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByText('1 investment account')).toBeInTheDocument();
        });
    });

    it('renders subtitle for properties only when no investments', async () => {
        mockGetHistory.mockResolvedValue({
            ...mockHistory,
            investment_account_count: 0,
            property_count: 2,
        });
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByText('2 properties')).toBeInTheDocument();
        });
    });

    it('time horizon select defaults to 2 years', async () => {
        mockGetHistory.mockResolvedValue(mockHistory);
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByText('Combined Portfolio History')).toBeInTheDocument();
        });

        const select = screen.getByLabelText('Time horizon') as HTMLSelectElement;
        expect(select.value).toBe('2');
    });

    it('changing time horizon triggers new API call', async () => {
        mockGetHistory.mockResolvedValue(mockHistory);
        const user = userEvent.setup();
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(mockGetHistory).toHaveBeenCalledWith(2);
        });

        await user.selectOptions(screen.getByLabelText('Time horizon'), '5');

        await waitFor(() => {
            expect(mockGetHistory).toHaveBeenCalledWith(5);
        });
    });

    it('passes transformed data to AreaChart', async () => {
        mockGetHistory.mockResolvedValue(mockHistory);
        render(<CombinedPortfolioChart />);

        await waitFor(() => {
            expect(screen.getByTestId('area-chart')).toBeInTheDocument();
        });

        const chartEl = screen.getByTestId('area-chart');
        const chartData = JSON.parse(chartEl.getAttribute('data-chart-data')!);

        expect(chartData).toHaveLength(3);
        expect(chartData[0]).toEqual({
            date: '2025-01-03',
            investmentValue: 100000,
            propertyEquity: 50000,
        });
    });
});
