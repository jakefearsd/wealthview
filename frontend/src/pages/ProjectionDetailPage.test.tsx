import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ProjectionCacheProvider } from '../context/ProjectionCacheContext';
import ProjectionDetailPage from './ProjectionDetailPage';
import type { Scenario } from '../types/projection';

const mockScenario: Scenario = {
    id: 'abc-123',
    name: 'Test Plan',
    retirement_date: '2045-01-01',
    end_age: 90,
    inflation_rate: 0.03,
    params_json: null,
    accounts: [
        { id: 'a1', linked_account_id: null, initial_balance: 100000, annual_contribution: 10000, expected_return: 0.07, account_type: 'taxable' },
    ],
    spending_profile: null,
    income_sources: [],
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
};

vi.mock('../api/projections', () => ({
    getScenario: vi.fn(),
    runProjection: vi.fn(),
    updateScenario: vi.fn(),
}));

vi.mock('../api/spendingProfiles', () => ({
    listSpendingProfiles: vi.fn().mockResolvedValue([]),
}));

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

vi.mock('../components/ProjectionChart', () => ({
    default: ({ mode }: { mode: string }) => <div data-testid={`chart-${mode}`}>Chart: {mode}</div>,
}));

vi.mock('../components/MilestoneStrip', () => ({
    default: () => <div data-testid="milestone-strip">Milestones</div>,
}));

import { useApiQuery } from '../hooks/useApiQuery';
const mockUseApiQuery = vi.mocked(useApiQuery);

function renderPage() {
    return render(
        <ProjectionCacheProvider>
            <MemoryRouter initialEntries={['/projections/abc-123']}>
                <Routes>
                    <Route path="/projections/:id" element={<ProjectionDetailPage />} />
                </Routes>
            </MemoryRouter>
        </ProjectionCacheProvider>,
    );
}

describe('ProjectionDetailPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders breadcrumb and scenario title', () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();

        expect(screen.getByRole('link', { name: 'Projections' })).toHaveAttribute('href', '/projections');
        expect(screen.getByRole('heading', { name: 'Test Plan' })).toBeInTheDocument();
    });

    it('renders config summary cards', () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();

        expect(screen.getByText('Retirement Date')).toBeInTheDocument();
        expect(screen.getByText('2045-01-01')).toBeInTheDocument();
        expect(screen.getByText('End Age')).toBeInTheDocument();
        expect(screen.getByText('90')).toBeInTheDocument();
    });

    it('shows run button', () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();

        expect(screen.getByRole('button', { name: /run projection/i })).toBeInTheDocument();
    });

    it('switches tabs when clicked', async () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenario, loading: false, error: null, refetch: vi.fn() });
        renderPage();

        // Simulate result by importing and calling runProjection mock
        const { runProjection } = await import('../api/projections');
        const mockRun = vi.mocked(runProjection);
        mockRun.mockResolvedValue({
            scenario_id: 'abc-123',
            yearly_data: [
                { year: 2024, age: 34, start_balance: 100000, contributions: 10000, growth: 7700, withdrawals: 0, end_balance: 117700, retired: false, traditional_balance: null, roth_balance: null, taxable_balance: null, roth_conversion_amount: null, tax_liability: null, essential_expenses: null, discretionary_expenses: null, income_streams_total: null, net_spending_need: null, spending_surplus: null, discretionary_after_cuts: null, rental_income_gross: null, rental_expenses_total: null, depreciation_total: null, rental_loss_applied: null, suspended_loss_carryforward: null, social_security_taxable: null, self_employment_tax: null },
            ],
            final_balance: 117700,
            years_in_retirement: 0,
            spending_feasibility: null,
        });

        await userEvent.click(screen.getByRole('button', { name: /run projection/i }));

        // Default tab is chart (balance)
        expect(screen.getByTestId('chart-balance')).toBeInTheDocument();

        // Click flows tab
        await userEvent.click(screen.getByRole('button', { name: /annual flows/i }));
        expect(screen.getByTestId('chart-flows')).toBeInTheDocument();

        // Click data table tab
        await userEvent.click(screen.getByRole('button', { name: /data table/i }));
        expect(screen.getByText('Year')).toBeInTheDocument();
    });
});
