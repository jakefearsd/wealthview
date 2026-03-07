import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { PropertyAnalyticsResponse } from '../types/property';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: () => ({ role: 'admin' }),
}));

vi.mock('recharts', () => ({
    LineChart: ({ children }: { children: React.ReactNode }) => <div data-testid="line-chart">{children}</div>,
    BarChart: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    Bar: () => <div />,
    Line: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    Legend: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number | null | undefined) => v != null ? `$${v.toLocaleString()}` : '$0',
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('../components/PropertyTransactionForm', () => ({
    default: () => <div data-testid="transaction-form" />,
}));

vi.mock('../components/HelpText', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));

vi.mock('../components/InfoSection', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: vi.fn() },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import PropertyDetailPage from './PropertyDetailPage';

const mockUseApiQuery = vi.mocked(useApiQuery);

const defaultReturn = { data: null, loading: false, error: null, refetch: vi.fn() };

function setupMocks(overrides: { property?: unknown; analytics?: unknown }) {
    let callCount = 0;
    mockUseApiQuery.mockImplementation(() => {
        callCount++;
        // Call order: 1=property, 2=cashFlow, 3=valuations, 4=analytics
        if (callCount === 1) return { ...defaultReturn, data: overrides.property ?? null } as ReturnType<typeof useApiQuery>;
        if (callCount === 4) return { ...defaultReturn, data: overrides.analytics ?? null } as ReturnType<typeof useApiQuery>;
        return defaultReturn as ReturnType<typeof useApiQuery>;
    });
}

const investmentAnalytics: PropertyAnalyticsResponse = {
    property_type: 'investment',
    total_appreciation: 50000,
    appreciation_percent: 25,
    mortgage_progress: {
        original_loan_amount: 200000,
        current_balance: 150000,
        principal_paid: 50000,
        percent_paid_off: 25,
        estimated_payoff_date: '2050-01-01',
        months_remaining: 288,
    },
    equity_growth: [
        { month: '2025-01', equity: 100000, property_value: 250000, mortgage_balance: 150000 },
    ],
    cap_rate: 7.5,
    annual_noi: 18000,
    cash_on_cash_return: 9.2,
    annual_net_cash_flow: 6000,
    total_cash_invested: 65000,
};

const primaryResidenceAnalytics: PropertyAnalyticsResponse = {
    property_type: 'primary_residence',
    total_appreciation: 30000,
    appreciation_percent: 15,
    mortgage_progress: null,
    equity_growth: [],
    cap_rate: null,
    annual_noi: null,
    cash_on_cash_return: null,
    annual_net_cash_flow: null,
    total_cash_invested: null,
};

const mockProperty = {
    id: 'prop-1',
    address: '123 Main St',
    property_type: 'investment',
    purchase_price: 200000,
    purchase_date: '2020-06-15',
    current_value: 250000,
    mortgage_balance: 150000,
    equity: 100000,
    use_computed_balance: false,
    has_loan_details: false,
};

function renderPage() {
    return render(
        <MemoryRouter initialEntries={['/properties/prop-1']}>
            <Routes>
                <Route path="/properties/:id" element={<PropertyDetailPage />} />
            </Routes>
        </MemoryRouter>
    );
}

describe('PropertyDetailPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders investment metrics for investment property', () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        renderPage();

        expect(screen.getByText('Investment Metrics')).toBeInTheDocument();
        expect(screen.getByText('7.50%')).toBeInTheDocument();
        expect(screen.getByText('9.20%')).toBeInTheDocument();
        expect(screen.getByText('$18,000')).toBeInTheDocument();
        expect(screen.getByText('$6,000')).toBeInTheDocument();
        expect(screen.getByText('$65,000')).toBeInTheDocument();
    });

    it('hides investment metrics for primary residence', () => {
        setupMocks({
            property: { ...mockProperty, property_type: 'primary_residence' },
            analytics: primaryResidenceAnalytics,
        });
        renderPage();

        expect(screen.queryByText('Investment Metrics')).not.toBeInTheDocument();
    });

    it('renders equity growth chart', () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        renderPage();

        expect(screen.getByText('Equity Growth')).toBeInTheDocument();
        expect(screen.getAllByTestId('line-chart').length).toBeGreaterThanOrEqual(1);
    });

    it('renders mortgage progress bar', () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        renderPage();

        expect(screen.getByText('Mortgage Payoff Progress')).toBeInTheDocument();
        expect(screen.getByText('25.0%')).toBeInTheDocument();
        expect(screen.getByText(/288 months/)).toBeInTheDocument();
    });

    it('renders year selector with options from purchase year to current year', () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        renderPage();

        expect(screen.getByText('Trailing 12 Months')).toBeInTheDocument();
        expect(screen.getByText('2020')).toBeInTheDocument();
    });
});
