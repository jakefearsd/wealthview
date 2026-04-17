import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/dashboard', () => ({
    getSnapshotProjection: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    selectStyle: {},
}));

vi.mock('../utils/errorMessage', () => ({
    extractErrorMessage: (err: unknown) => (err instanceof Error ? err.message : 'unknown error'),
}));

vi.mock('recharts', () => ({
    AreaChart: ({ children }: { children: React.ReactNode }) => <div data-testid="area-chart">{children}</div>,
    Area: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    Legend: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { getSnapshotProjection } from '../api/dashboard';
import SnapshotProjectionChart from './SnapshotProjectionChart';

const projection = {
    horizon_years: 10,
    portfolio_cagr: 0.07,
    investment_account_count: 3,
    property_count: 1,
    data_points: [
        { date: '2026-01-01', year: 0, investment_value: 300000, property_equity: 150000 },
        { date: '2036-01-01', year: 10, investment_value: 600000, property_equity: 200000 },
    ],
};

describe('SnapshotProjectionChart', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders chart after data loads', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getSnapshotProjection).mockResolvedValue(projection as any);
        render(<SnapshotProjectionChart />);
        expect(await screen.findByTestId('area-chart')).toBeInTheDocument();
    });

    it('shows error state with the extracted message', async () => {
        vi.mocked(getSnapshotProjection).mockRejectedValue(new Error('api exploded'));
        render(<SnapshotProjectionChart />);
        expect(await screen.findByText(/Failed to load projection: api exploded/)).toBeInTheDocument();
    });

    it('refetches when the horizon changes', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getSnapshotProjection).mockResolvedValue(projection as any);
        render(<SnapshotProjectionChart />);
        await screen.findByTestId('area-chart');

        fireEvent.change(screen.getByRole('combobox'), { target: { value: '20' } });
        await waitFor(() => {
            expect(getSnapshotProjection).toHaveBeenCalledWith(20);
        });
    });
});
