import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { DashboardSummary } from '../types/dashboard';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../components/CombinedPortfolioChart', () => ({
    default: () => <div data-testid="combined-portfolio-chart" />,
}));

vi.mock('../components/SummaryCard', () => ({
    default: ({ label, value }: { label: string; value: string }) => (
        <div data-testid={`summary-card-${label}`}>{label}: {value}</div>
    ),
}));

vi.mock('recharts', () => ({
    PieChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    Pie: () => <div />,
    Cell: () => <div />,
    Tooltip: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
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
        { name: 'Brokerage', type: 'brokerage', balance: 250000 },
        { name: 'IRA', type: 'ira', balance: 50000 },
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
        render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(screen.getByText('Loading dashboard...')).toBeInTheDocument();
    });

    it('renders error state', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: 'Network error', refetch: vi.fn() });
        render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(screen.getByText('Error: Network error')).toBeInTheDocument();
    });

    it('returns null when data is null and not loading', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: false, error: null, refetch: vi.fn() });
        const { container } = render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(container.innerHTML).toBe('');
    });

    it('renders summary cards with formatted values', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(screen.getByTestId('summary-card-Net Worth')).toHaveTextContent('$500,000');
        expect(screen.getByTestId('summary-card-Investments')).toHaveTextContent('$300,000');
        expect(screen.getByTestId('summary-card-Cash')).toHaveTextContent('$50,000');
        expect(screen.getByTestId('summary-card-Property Equity')).toHaveTextContent('$150,000');
    });

    it('renders CombinedPortfolioChart component', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(screen.getByTestId('combined-portfolio-chart')).toBeInTheDocument();
    });

    it('renders accounts table with data', () => {
        mockUseApiQuery.mockReturnValue({ data: mockSummary, loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(screen.getByText('Brokerage')).toBeInTheDocument();
        expect(screen.getByText('IRA')).toBeInTheDocument();
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
        render(<MemoryRouter><DashboardPage /></MemoryRouter>);

        expect(screen.getByText('No allocation data')).toBeInTheDocument();
    });
});
