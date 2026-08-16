import { screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRoute } from '../test-utils';
import type { PropertyAnalyticsResponse } from '../types/property';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../context/AuthContext', () => ({
    useAuth: vi.fn(),
}));

vi.mock('recharts');

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number | null | undefined) => v != null ? `$${v.toLocaleString()}` : '$0',
    // handleStartEdit converts stored fractions back to whole percents for the form; mirrors the
    // real implementation in @wealthview/shared.
    toPercent: (decimal: number) => parseFloat((decimal * 100).toPrecision(10)),
    // CurrencyInput, rendered by the edit form, formats/parses through these.
    formatCurrencyInput: (v: string) => v,
    parseCurrencyInput: (v: string) => v,
    formatWholeCurrency: (v: number) => `$${Math.round(v).toLocaleString()}`,
    formatCompactCurrency: (v: number) => `$${v.toLocaleString()}`,
    formatPercent: (v: number) => `${v}%`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    // PropertyForm reaches for these once the edit form opens.
    inputStyle: {},
    labelStyle: {},
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

vi.mock('../api/properties', () => ({
    getProperty: vi.fn(),
    updateProperty: vi.fn(),
    addPropertyExpense: vi.fn(),
    deletePropertyExpense: vi.fn(),
    getCashFlow: vi.fn(),
    getValuationHistory: vi.fn(),
    refreshValuation: vi.fn(),
    selectZpid: vi.fn(),
    getPropertyAnalytics: vi.fn(),
    listPropertyExpenses: vi.fn(),
    getDepreciationSchedule: vi.fn(),
}));

vi.mock('../api/incomeSources', () => ({
    listIncomeSources: vi.fn(),
}));

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(),
    toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { useAuth } from '../context/AuthContext';
import { refreshValuation, selectZpid, getDepreciationSchedule } from '../api/properties';
import PropertyDetailPage from './PropertyDetailPage';
import { authAs } from '../testutil/auth';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockUseAuth = vi.mocked(useAuth);

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
    return renderWithRoute(<PropertyDetailPage />, {
        path: '/properties/:id',
        entry: '/properties/prop-1',
    });
}

describe('PropertyDetailPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        // PropertyAnalyticsSection fetches this as soon as a depreciation method is set, and
        // awaits it — a bare vi.fn() returns undefined and blows up on .then().
        vi.mocked(getDepreciationSchedule).mockResolvedValue({
            schedule: [], bonus_depreciation_rate: null,
            cost_seg_allocations: null, class_breakdowns: null,
        } as never);
        mockUseAuth.mockReturnValue(authAs('admin'));
    });

    // === write gating ===
    //
    // PUT /api/v1/properties/** is open to ADMIN, MEMBER and SUPER_ADMIN (SecurityConfig), so the
    // edit control must follow that same set.

    it('shows the edit control to a super_admin', () => {
        mockUseAuth.mockReturnValue(authAs('super_admin'));
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });

        renderPage();

        expect(screen.getByRole('button', { name: /^Edit$/i })).toBeInTheDocument();
    });

    it('hides the edit control from a viewer', () => {
        mockUseAuth.mockReturnValue(authAs('viewer'));
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });

        renderPage();

        expect(screen.queryByRole('button', { name: /^Edit$/i })).not.toBeInTheDocument();
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

    // === Zillow valuation refresh ===
    //
    // handleValuationResult fans out three ways on the SAME response shape, and each outcome has a
    // different user consequence: a value written to the property, a disambiguation list, or a
    // plain failure. None of the three was covered, nor was the 503 that means the scraper is
    // switched off entirely — which must not read as a generic failure.

    const refreshButton = () =>
        screen.getAllByRole('button', { name: /Refresh|Fetch.*Zillow|Update Valuation/i })[0];

    it('writes the new value and refetches when Zillow returns a single match', async () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        vi.mocked(refreshValuation).mockResolvedValue({ status: 'updated', value: 312500 } as never);
        renderPage();

        fireEvent.click(refreshButton());

        await waitFor(() => expect(toastSuccess)
            .toHaveBeenCalledWith(expect.stringContaining('312,500')));
    });

    it('offers the candidates for disambiguation when Zillow returns several matches', async () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        vi.mocked(refreshValuation).mockResolvedValue({
            status: 'multiple_matches',
            candidates: [
                { zpid: '111', address: '123 Main St Unit A', zestimate: 310000 },
                { zpid: '222', address: '123 Main St Unit B', zestimate: 325000 },
            ],
        } as never);
        renderPage();

        fireEvent.click(refreshButton());

        expect(await screen.findByText('123 Main St Unit A')).toBeInTheDocument();
        expect(screen.getByText('123 Main St Unit B')).toBeInTheDocument();
        expect(toastSuccess).not.toHaveBeenCalled();
    });

    it('reports a plain miss when Zillow matches nothing', async () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        vi.mocked(refreshValuation).mockResolvedValue({ status: 'no_match' } as never);
        renderPage();

        fireEvent.click(refreshButton());

        await waitFor(() => expect(toastError)
            .toHaveBeenCalledWith('No Zillow results found for this address'));
    });

    it('explains that the valuation service is disabled on a 503 rather than saying it failed', async () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        vi.mocked(refreshValuation).mockRejectedValue({ response: { status: 503 } });
        renderPage();

        fireEvent.click(refreshButton());

        await waitFor(() => expect(toastError)
            .toHaveBeenCalledWith(expect.stringContaining('not enabled')));
    });

    it('falls back to a generic message for any other valuation failure', async () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        vi.mocked(refreshValuation).mockRejectedValue({ response: { status: 500 } });
        renderPage();

        fireEvent.click(refreshButton());

        await waitFor(() => expect(toastError)
            .toHaveBeenCalledWith('Failed to refresh valuation'));
    });

    it('resolves the address once a candidate is chosen, and clears the candidate list', async () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        vi.mocked(refreshValuation).mockResolvedValue({
            status: 'multiple_matches',
            candidates: [{ zpid: '111', address: '123 Main St Unit A', zestimate: 310000 }],
        } as never);
        vi.mocked(selectZpid).mockResolvedValue({ status: 'updated', value: 310000 } as never);
        renderPage();

        fireEvent.click(refreshButton());
        const candidate = await screen.findByText('123 Main St Unit A');

        fireEvent.click(candidate.closest('button') ?? candidate);

        await waitFor(() => expect(selectZpid).toHaveBeenCalledWith('prop-1', '111'));
        await waitFor(() =>
            expect(screen.queryByText('123 Main St Unit A')).not.toBeInTheDocument());
    });

    // === edit form ===

    it('opens the edit form prefilled from the property, converting stored fractions to percents', () => {
        setupMocks({
            property: {
                ...mockProperty,
                has_loan_details: true,
                loan_amount: 160000,
                annual_interest_rate: 0.0625,
                annual_appreciation_rate: 0.035,
                bonus_depreciation_rate: 0.6,
            },
            analytics: investmentAnalytics,
        });
        renderPage();

        fireEvent.click(screen.getByRole('button', { name: /^Edit$/i }));

        // Rates are stored as fractions and edited as whole percents.
        expect(screen.getByDisplayValue('6.25')).toBeInTheDocument();
        expect(screen.getByDisplayValue('3.5')).toBeInTheDocument();
    });

    it('closes the edit form on cancel', () => {
        setupMocks({ property: mockProperty, analytics: investmentAnalytics });
        renderPage();
        fireEvent.click(screen.getByRole('button', { name: /^Edit$/i }));
        expect(screen.getByRole('button', { name: /^Cancel$/i })).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: /^Cancel$/i }));

        expect(screen.queryByRole('button', { name: /^Cancel$/i })).not.toBeInTheDocument();
    });

    it('defaults the useful life to 27.5 years when the property carries none', () => {
        setupMocks({
            property: { ...mockProperty, depreciation_method: 'straight_line', useful_life_years: null },
            analytics: investmentAnalytics,
        });
        renderPage();

        fireEvent.click(screen.getByRole('button', { name: /^Edit$/i }));

        expect(screen.getByDisplayValue('27.5')).toBeInTheDocument();
    });
});
