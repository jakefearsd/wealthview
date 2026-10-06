import { screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import type { DashboardSummary } from '../types/dashboard';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../components/CombinedPortfolioChart', () => ({
    default: () => <div data-testid="combined-portfolio-chart" />,
}));

// SnapshotProjectionChart now uses the (mocked) useApiQuery hook, whose stubbed
// return value is shaped for the dashboard summary — stub the chart out instead.
vi.mock('../components/SnapshotProjectionChart', () => ({
    default: () => <div data-testid="snapshot-projection-chart" />,
}));

vi.mock('../components/SummaryCard', () => ({
    default: ({ label, value }: { label: string; value: string }) => (
        <div data-testid={`summary-card-${label}`}>{label}: {value}</div>
    ),
}));

vi.mock('recharts');

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
    selectStyle: {},
}));

import { useApiQuery } from '../hooks/useApiQuery';
import DashboardPage from './DashboardPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

const mockSummary: DashboardSummary = {
    net_worth: 500000,
    total_investments: 300000,
    total_cash: 50000,
    total_property_equity: 150000,
    accounts: [
        { name: 'Main taxable', type: 'brokerage', balance: 250000 },
        { name: 'Rollover', type: 'ira', balance: 50000 },
        { name: 'Work plan', type: '401k', balance: 20000 },
    ],
    allocation: [
        { category: 'Investments', value: 300000, percentage: 60 },
        { category: 'Property', value: 150000, percentage: 30 },
    ],
};

describe('DashboardPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders loading state', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByText('Loading dashboard...')).toBeInTheDocument();
    });

    it('renders error state', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: 'Network error', refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByText('Something went wrong')).toBeInTheDocument();
        expect(screen.getByText('Network error')).toBeInTheDocument();
    });

    it('returns null when data is null and not loading', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() });
        const { container } = renderWithRouter(<DashboardPage />);

        expect(container.innerHTML).toBe('');
    });

    it('renders summary cards with formatted values', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByTestId('summary-card-Net Worth')).toHaveTextContent('$500,000');
        expect(screen.getByTestId('summary-card-Investments')).toHaveTextContent('$300,000');
        expect(screen.getByTestId('summary-card-Cash')).toHaveTextContent('$50,000');
        expect(screen.getByTestId('summary-card-Property Equity')).toHaveTextContent('$150,000');
    });

    it('renders CombinedPortfolioChart component', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByTestId('combined-portfolio-chart')).toBeInTheDocument();
    });

    it('renders accounts table with data', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByText('Main taxable')).toBeInTheDocument();
        expect(screen.getByText('Rollover')).toBeInTheDocument();
        expect(screen.getByText('Name')).toBeInTheDocument();
        expect(screen.getByText('Type')).toBeInTheDocument();
        expect(screen.getByText('Balance')).toBeInTheDocument();
    });

    it('renders no-data allocation message when allocation is empty', () => {
        mockUseApiQuery.mockReturnValue({
            data: { ...mockSummary, allocation: [] },
            loading: false,
            error: null,
            refetch: vi.fn(),
        });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByText('No allocation data')).toBeInTheDocument();
    });

    it('labels account types the same way as the Accounts page', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.getByText('IRA')).toBeInTheDocument();
        expect(screen.getByText('401(k)')).toBeInTheDocument();
        expect(screen.getByText('Brokerage')).toBeInTheDocument();
        expect(screen.queryByText('401k')).not.toBeInTheDocument();
    });

    it('retries by refetching instead of reloading the page', () => {
        const refetch = vi.fn();
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: 'Network error', refetch });
        renderWithRouter(<DashboardPage />);

        fireEvent.click(screen.getByRole('button', { name: 'Retry' }));

        expect(refetch).toHaveBeenCalled();
    });

    it('keeps the dashboard on screen while a refetch is in flight', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: true, error: null, refetch: vi.fn() });
        renderWithRouter(<DashboardPage />);

        expect(screen.queryByText('Loading dashboard...')).not.toBeInTheDocument();
        expect(screen.getByTestId('summary-card-Net Worth')).toBeInTheDocument();
    });
});
