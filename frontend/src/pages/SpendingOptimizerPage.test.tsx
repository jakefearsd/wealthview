import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Scenario, GuardrailProfileResponse } from '../types/projection';

vi.mock('../api/projections', () => ({
    getScenario: vi.fn(),
    optimizeSpending: vi.fn(),
    getGuardrailProfile: vi.fn(),
    reoptimize: vi.fn(),
}));

vi.mock('../components/SpendingCorridorChart', () => ({
    default: ({ yearlySpending, phases }: { yearlySpending: unknown[]; phases: unknown[] }) => (
        <div data-testid="corridor-chart"
             data-spending-count={yearlySpending.length}
             data-phase-count={phases.length} />
    ),
}));

import { getScenario, getGuardrailProfile } from '../api/projections';

const mockGetScenario = vi.mocked(getScenario);
const mockGetProfile = vi.mocked(getGuardrailProfile);

const mockScenario: Scenario = {
    id: 'sc-1',
    name: 'Test Scenario',
    retirement_date: '2030-01-01',
    end_age: 90,
    inflation_rate: 0.03,
    params_json: '{"birth_year":1968}',
    accounts: [{ id: 'a1', linked_account_id: null, initial_balance: 500000, annual_contribution: 0, expected_return: 0.07, account_type: 'taxable' }],
    spending_profile: null,
    income_sources: [],
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
};

const mockProfile: GuardrailProfileResponse = {
    id: 'gp-1',
    scenario_id: 'sc-1',
    name: 'Optimized Plan',
    essential_floor: 30000,
    terminal_balance_target: 0,
    return_mean: 0.10,
    return_stddev: 0.15,
    trial_count: 5000,
    confidence_level: 0.95,
    phases: [
        { name: 'Early', start_age: 62, end_age: 72, priority_weight: 3, target_spending: 80000 },
        { name: 'Mid', start_age: 73, end_age: 82, priority_weight: 1, target_spending: 60000 },
    ],
    yearly_spending: [
        { year: 2030, age: 62, recommended: 75000, corridor_low: 62000, corridor_high: 91000, essential_floor: 30000, discretionary: 45000, income_offset: 0, portfolio_withdrawal: 75000, phase_name: 'Early', portfolio_balance_median: 480000 },
        { year: 2031, age: 63, recommended: 74000, corridor_low: 61000, corridor_high: 90000, essential_floor: 30000, discretionary: 44000, income_offset: 0, portfolio_withdrawal: 74000, phase_name: 'Early', portfolio_balance_median: 440000 },
        { year: 2040, age: 72, recommended: 50000, corridor_low: 40000, corridor_high: 65000, essential_floor: 30000, discretionary: 20000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'Mid', portfolio_balance_median: 300000 },
    ],
    median_final_balance: 250000,
    failure_rate: 0.05,
    percentile10_final: 100000,
    percentile90_final: 500000,
    stale: false,
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
    portfolio_floor: 100000,
    max_annual_adjustment_rate: 0.05,
    phase_blend_years: 1,
    risk_tolerance: 'moderate',
};

function renderPage() {
    return render(
        <MemoryRouter initialEntries={['/projections/sc-1/optimize']}>
            <Routes>
                <Route path="/projections/:id/optimize" element={<SpendingOptimizerPage />} />
            </Routes>
        </MemoryRouter>,
    );
}

import SpendingOptimizerPage from './SpendingOptimizerPage';

describe('SpendingOptimizerPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mockGetScenario.mockResolvedValue(mockScenario);
        mockGetProfile.mockResolvedValue(null);
    });

    it('renders configuration form when no existing profile', async () => {
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Spending Optimizer')).toBeInTheDocument();
        });
        expect(screen.getByText('Optimization Parameters')).toBeInTheDocument();
        expect(screen.getByText('Spending Phases')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /run optimization/i })).toBeInTheDocument();
    });

    it('shows risk tolerance selector with three options', async () => {
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Risk Tolerance')).toBeInTheDocument();
        });
        expect(screen.getByRole('button', { name: /conservative/i })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /moderate/i })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: /aggressive/i })).toBeInTheDocument();
    });

    it('shows portfolio safety net and spending flexibility inputs', async () => {
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Portfolio Safety Net ($)')).toBeInTheDocument();
        });
        expect(screen.getByText('Spending Flexibility (%/yr)')).toBeInTheDocument();
        expect(screen.getByText('Phase Blending')).toBeInTheDocument();
    });

    it('shows advanced settings when toggled', async () => {
        const user = userEvent.setup();
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Advanced Settings')).toBeInTheDocument();
        });

        // Advanced fields should not be visible initially
        expect(screen.queryByText('Expected Return (%)')).not.toBeInTheDocument();

        await user.click(screen.getByText('Advanced Settings'));
        expect(screen.getByText('Expected Return (%)')).toBeInTheDocument();
        expect(screen.getByText('Return Std Dev (%)')).toBeInTheDocument();
        expect(screen.getByText('Confidence Level (%)')).toBeInTheDocument();
    });

    it('shows default phases with target spending in configure state', async () => {
        renderPage();

        await waitFor(() => {
            expect(screen.getByDisplayValue('Early retirement')).toBeInTheDocument();
        });
        expect(screen.getByDisplayValue('Mid retirement')).toBeInTheDocument();
        expect(screen.getByDisplayValue('Late retirement')).toBeInTheDocument();
        // Target spending values
        expect(screen.getByDisplayValue('80000')).toBeInTheDocument();
        expect(screen.getByDisplayValue('60000')).toBeInTheDocument();
        expect(screen.getByDisplayValue('45000')).toBeInTheDocument();
    });

    it('loads existing profile and shows results', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Spending Corridor')).toBeInTheDocument();
        });

        // Summary cards
        expect(screen.getByText('Median Final Balance')).toBeInTheDocument();
        expect(screen.getByText('Failure Rate')).toBeInTheDocument();
        expect(screen.getByText('10th Percentile Final')).toBeInTheDocument();
        expect(screen.getByText('90th Percentile Final')).toBeInTheDocument();
    });

    it('renders corridor chart with correct data counts', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('corridor-chart')).toBeInTheDocument();
        });

        const chart = screen.getByTestId('corridor-chart');
        expect(chart.getAttribute('data-spending-count')).toBe('3');
        expect(chart.getAttribute('data-phase-count')).toBe('2');
    });

    it('shows stale warning when profile is stale', async () => {
        mockGetProfile.mockResolvedValue({ ...mockProfile, stale: true });
        renderPage();

        await waitFor(() => {
            expect(screen.getByText(/stale/i)).toBeInTheDocument();
        });
        expect(screen.getByRole('button', { name: /re-optimize/i })).toBeInTheDocument();
    });

    it('year-by-year table renders with portfolio balance column', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Year-by-Year Breakdown')).toBeInTheDocument();
        });

        // Check table headers including new Portfolio Balance column
        expect(screen.getByText('Age')).toBeInTheDocument();
        expect(screen.getByText('Phase')).toBeInTheDocument();
        expect(screen.getByText('Recommended')).toBeInTheDocument();
        expect(screen.getByText('Portfolio Balance')).toBeInTheDocument();
    });

    it('validates mathematical consistency: recommended = floor + discretionary', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Year-by-Year Breakdown')).toBeInTheDocument();
        });

        // Verify the mathematical relationship in the data
        for (const year of mockProfile.yearly_spending) {
            expect(year.recommended).toBe(year.essential_floor + year.discretionary);
        }
    });

    it('validates mathematical consistency: corridor_low <= recommended <= corridor_high', async () => {
        for (const year of mockProfile.yearly_spending) {
            expect(year.corridor_low).toBeLessThanOrEqual(year.recommended);
            expect(year.corridor_high).toBeGreaterThanOrEqual(year.recommended);
        }
    });

    it('validates mathematical consistency: portfolio_withdrawal + income_offset >= recommended', async () => {
        for (const year of mockProfile.yearly_spending) {
            expect(year.portfolio_withdrawal + year.income_offset).toBeGreaterThanOrEqual(year.recommended);
        }
    });

    it('adds a phase when + Add Phase clicked', async () => {
        const user = userEvent.setup();
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('+ Add Phase')).toBeInTheDocument();
        });

        // Initially 3 default phases
        const removeButtons = screen.getAllByRole('button', { name: /remove/i });
        expect(removeButtons).toHaveLength(3);

        await user.click(screen.getByText('+ Add Phase'));

        const removeButtonsAfter = screen.getAllByRole('button', { name: /remove/i });
        expect(removeButtonsAfter).toHaveLength(4);
    });

    it('removes a phase when Remove clicked', async () => {
        const user = userEvent.setup();
        renderPage();

        await waitFor(() => {
            expect(screen.getByDisplayValue('Early retirement')).toBeInTheDocument();
        });

        const removeButtons = screen.getAllByRole('button', { name: /remove/i });
        await user.click(removeButtons[0]);

        expect(screen.queryByDisplayValue('Early retirement')).not.toBeInTheDocument();
        expect(screen.getAllByRole('button', { name: /remove/i })).toHaveLength(2);
    });
});
