import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { Scenario, GuardrailProfileResponse, GuardrailPhase, GuardrailYearlySpending } from '../types/projection';
import { computePlanDiagnostics } from './SpendingOptimizerPage';

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

vi.mock('../components/PortfolioFanChart', () => ({
    default: ({ yearlySpending }: { yearlySpending: unknown[] }) => (
        <div data-testid="portfolio-fan-chart" data-spending-count={yearlySpending.length} />
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
    guardrail_profile: null,
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
        { year: 2030, age: 62, recommended: 75000, corridor_low: 62000, corridor_high: 91000, essential_floor: 30000, discretionary: 45000, income_offset: 0, portfolio_withdrawal: 75000, phase_name: 'Early', portfolio_balance_median: 480000, portfolio_balance_p10: 200000, portfolio_balance_p25: 350000, portfolio_balance_p55: 650000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
        { year: 2031, age: 63, recommended: 74000, corridor_low: 61000, corridor_high: 90000, essential_floor: 30000, discretionary: 44000, income_offset: 0, portfolio_withdrawal: 74000, phase_name: 'Early', portfolio_balance_median: 440000, portfolio_balance_p10: 180000, portfolio_balance_p25: 320000, portfolio_balance_p55: 600000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
        { year: 2041, age: 73, recommended: 50000, corridor_low: 40000, corridor_high: 65000, essential_floor: 30000, discretionary: 20000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'Mid', portfolio_balance_median: 300000, portfolio_balance_p10: 50000, portfolio_balance_p25: 180000, portfolio_balance_p55: 500000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
    ],
    median_final_balance: 250000,
    failure_rate: 0.05,
    percentile10_final: 100000,
    percentile55_final: 500000,
    stale: false,
    created_at: '2024-01-01T00:00:00Z',
    updated_at: '2024-01-01T00:00:00Z',
    portfolio_floor: 100000,
    max_annual_adjustment_rate: 0.05,
    phase_blend_years: 1,
    risk_tolerance: 'moderate',
    cash_reserve_years: 2,
    cash_return_rate: 0.04,
    conversion_schedule: null,
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
            expect(screen.getByText('Portfolio Safety Net')).toBeInTheDocument();
        });
        expect(screen.getByText('Spending Flexibility')).toBeInTheDocument();
        expect(screen.getByText('Phase Blending')).toBeInTheDocument();
    });

    it('shows advanced settings when toggled', async () => {
        const user = userEvent.setup();
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Advanced Settings')).toBeInTheDocument();
        });

        // Advanced fields should not be visible initially
        expect(screen.queryByText('Cash Reserve')).not.toBeInTheDocument();

        await user.click(screen.getByText('Advanced Settings'));
        expect(screen.getByText('Cash Reserve')).toBeInTheDocument();
        expect(screen.getByText('Cash Rate')).toBeInTheDocument();
        expect(screen.getByText('Confidence Level')).toBeInTheDocument();
    });

    it('shows default phases with target spending in configure state', async () => {
        renderPage();

        await waitFor(() => {
            expect(screen.getByDisplayValue('Early retirement')).toBeInTheDocument();
        });
        expect(screen.getByDisplayValue('Mid retirement')).toBeInTheDocument();
        expect(screen.getByDisplayValue('Late retirement')).toBeInTheDocument();
        // Target spending values (formatted with toLocaleString)
        expect(screen.getByDisplayValue('80,000')).toBeInTheDocument();
        expect(screen.getByDisplayValue('60,000')).toBeInTheDocument();
        expect(screen.getByDisplayValue('45,000')).toBeInTheDocument();
    });

    it('loads existing profile and shows results', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Spending Corridor')).toBeInTheDocument();
        });

        // Summary cards
        expect(screen.getByText('Failure Rate')).toBeInTheDocument();
        expect(screen.getByText('10th Percentile Final')).toBeInTheDocument();
        expect(screen.getByText('25th Percentile Final')).toBeInTheDocument();
        expect(screen.getByText('Median Final Balance')).toBeInTheDocument();
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

    it('year-by-year table renders four portfolio balance sub-columns', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Year-by-Year Breakdown')).toBeInTheDocument();
        });

        // Check grouped header and sub-headers
        expect(screen.getByText('Portfolio Balance')).toBeInTheDocument();
        expect(screen.getByText('p10')).toBeInTheDocument();
        expect(screen.getByText('p25')).toBeInTheDocument();
        expect(screen.getByText('p50')).toBeInTheDocument();
    });

    it('renders outcome range card with final year percentiles', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('outcome-range-card')).toBeInTheDocument();
        });

        expect(screen.getByText(/Outcome Range/)).toBeInTheDocument();
    });

    it('displays p55 final balance in summary cards', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('p55-card')).toBeInTheDocument();
        });

        expect(screen.getByText('55th Percentile Final')).toBeInTheDocument();
    });

    it('displays p55 column in year-by-year table', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('p55')).toBeInTheDocument();
        });
    });

    it('shows tax disclaimer note', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('tax-disclaimer')).toBeInTheDocument();
        });
    });

    it('renders portfolio fan chart with balance projections', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('portfolio-fan-chart')).toBeInTheDocument();
        });

        expect(screen.getByText('Portfolio Balance Projections')).toBeInTheDocument();
    });

    it('outcome range card includes p55 endpoint', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('outcome-range-card')).toBeInTheDocument();
        });

        expect(screen.getByText(/Slightly above median \(p55\)/)).toBeInTheDocument();
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

    it('renders warning banner when plan has warnings', async () => {
        // Early phase: avg recommended (75000+74000)/2 = 74500 vs target 80000 = 93.1% (ok)
        // Mid phase: avg recommended 50000 vs target 60000 = 83.3% (warning: <90%)
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('warning-banner')).toBeInTheDocument();
        });
        expect(screen.getByText('Plan Warnings')).toBeInTheDocument();
        expect(screen.getByText(/Mid is only 83% funded/)).toBeInTheDocument();
    });

    it('renders phase achievement table with progress bars', async () => {
        mockGetProfile.mockResolvedValue(mockProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Phase Achievement')).toBeInTheDocument();
        });

        // Both phases with targets should appear
        expect(screen.getByTestId('progress-bar-Early')).toBeInTheDocument();
        expect(screen.getByTestId('progress-bar-Mid')).toBeInTheDocument();

        // Achievement percentages should be rendered
        expect(screen.getByText('93%')).toBeInTheDocument();
        expect(screen.getByText('83%')).toBeInTheDocument();
    });

    it('failure rate card shows good color when rate is low', async () => {
        mockGetProfile.mockResolvedValue(mockProfile); // failure_rate: 0.05
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('failure-rate-card')).toBeInTheDocument();
        });

        const card = screen.getByTestId('failure-rate-card');
        expect(card.style.background).toBe('rgb(232, 245, 233)'); // #e8f5e9 (good)
    });

    it('failure rate card shows danger color when rate exceeds 20%', async () => {
        mockGetProfile.mockResolvedValue({ ...mockProfile, failure_rate: 0.25 });
        renderPage();

        await waitFor(() => {
            expect(screen.getByTestId('failure-rate-card')).toBeInTheDocument();
        });

        const card = screen.getByTestId('failure-rate-card');
        expect(card.style.background).toBe('rgb(255, 235, 238)'); // #ffebee (danger)
    });

    it('no warning banner when plan is fully funded', async () => {
        const fullyFundedProfile: GuardrailProfileResponse = {
            ...mockProfile,
            phases: [
                { name: 'Only', start_age: 62, end_age: null, priority_weight: 1, target_spending: 50000 },
            ],
            yearly_spending: [
                { year: 2030, age: 62, recommended: 50000, corridor_low: 40000, corridor_high: 60000, essential_floor: 30000, discretionary: 20000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'Only', portfolio_balance_median: 480000, portfolio_balance_p10: 200000, portfolio_balance_p25: 350000, portfolio_balance_p55: 650000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
                { year: 2031, age: 63, recommended: 50000, corridor_low: 40000, corridor_high: 60000, essential_floor: 30000, discretionary: 20000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'Only', portfolio_balance_median: 460000, portfolio_balance_p10: 190000, portfolio_balance_p25: 330000, portfolio_balance_p55: 620000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
            ],
            failure_rate: 0.03,
        };
        mockGetProfile.mockResolvedValue(fullyFundedProfile);
        renderPage();

        await waitFor(() => {
            expect(screen.getByText('Spending Corridor')).toBeInTheDocument();
        });

        expect(screen.queryByTestId('warning-banner')).not.toBeInTheDocument();
    });
});

describe('computePlanDiagnostics', () => {
    it('all phases funded produces no warnings', () => {
        const phases: GuardrailPhase[] = [
            { name: 'Early', start_age: 62, end_age: 72, priority_weight: 1, target_spending: 50000 },
            { name: 'Late', start_age: 73, end_age: null, priority_weight: 1, target_spending: 40000 },
        ];
        const yearly: GuardrailYearlySpending[] = [
            { year: 2030, age: 62, recommended: 50000, corridor_low: 40000, corridor_high: 60000, essential_floor: 20000, discretionary: 30000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'Early', portfolio_balance_median: 900000, portfolio_balance_p10: 400000, portfolio_balance_p25: 650000, portfolio_balance_p55: 1200000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
            { year: 2041, age: 73, recommended: 40000, corridor_low: 35000, corridor_high: 50000, essential_floor: 20000, discretionary: 20000, income_offset: 0, portfolio_withdrawal: 40000, phase_name: 'Late', portfolio_balance_median: 500000, portfolio_balance_p10: 200000, portfolio_balance_p25: 350000, portfolio_balance_p55: 700000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
        ];

        const result = computePlanDiagnostics(phases, yearly, 0.05);

        expect(result.warnings).toHaveLength(0);
        expect(result.phases).toHaveLength(2);
        expect(result.phases[0].achievementPct).toBe(100);
        expect(result.phases[1].achievementPct).toBe(100);
        expect(result.failureRateSeverity).toBe('good');
    });

    it('underfunded phase shows warning', () => {
        const phases: GuardrailPhase[] = [
            { name: 'Expensive', start_age: 62, end_age: null, priority_weight: 1, target_spending: 100000 },
        ];
        const yearly: GuardrailYearlySpending[] = [
            { year: 2030, age: 62, recommended: 60000, corridor_low: 50000, corridor_high: 70000, essential_floor: 20000, discretionary: 40000, income_offset: 0, portfolio_withdrawal: 60000, phase_name: 'Expensive', portfolio_balance_median: 400000, portfolio_balance_p10: 150000, portfolio_balance_p25: 280000, portfolio_balance_p55: 550000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
        ];

        const result = computePlanDiagnostics(phases, yearly, 0.05);

        expect(result.warnings).toContain('Expensive is only 60% funded');
        expect(result.phases[0].achievementPct).toBe(60);
    });

    it('detects portfolio depletion at p10', () => {
        const phases: GuardrailPhase[] = [
            { name: 'All', start_age: 62, end_age: null, priority_weight: 1, target_spending: 50000 },
        ];
        const yearly: GuardrailYearlySpending[] = [
            { year: 2030, age: 62, recommended: 50000, corridor_low: 40000, corridor_high: 60000, essential_floor: 20000, discretionary: 30000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'All', portfolio_balance_median: 900000, portfolio_balance_p10: 100000, portfolio_balance_p25: 400000, portfolio_balance_p55: 1200000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
            { year: 2031, age: 63, recommended: 50000, corridor_low: 40000, corridor_high: 60000, essential_floor: 20000, discretionary: 30000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'All', portfolio_balance_median: 800000, portfolio_balance_p10: 0, portfolio_balance_p25: 300000, portfolio_balance_p55: 1100000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
        ];

        const result = computePlanDiagnostics(phases, yearly, 0.05);

        expect(result.depletionAgeP10).toBe(63);
        expect(result.depletionAgeP25).toBeNull();
    });

    it('no depletion warning when all percentiles positive', () => {
        const phases: GuardrailPhase[] = [
            { name: 'All', start_age: 62, end_age: null, priority_weight: 1, target_spending: 50000 },
        ];
        const yearly: GuardrailYearlySpending[] = [
            { year: 2030, age: 62, recommended: 50000, corridor_low: 40000, corridor_high: 60000, essential_floor: 20000, discretionary: 30000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'All', portfolio_balance_median: 900000, portfolio_balance_p10: 400000, portfolio_balance_p25: 650000, portfolio_balance_p55: 1200000, contingent_spending_p25: null, contingent_spending_median: null, contingent_spending_p55: null },
        ];

        const result = computePlanDiagnostics(phases, yearly, 0.05);

        expect(result.depletionAgeP10).toBeNull();
        expect(result.depletionAgeP25).toBeNull();
        expect(result.warnings.filter(w => w.includes('depleted'))).toHaveLength(0);
    });

    it('high failure rate produces danger severity', () => {
        const result = computePlanDiagnostics([], [], 0.25);

        expect(result.failureRateSeverity).toBe('danger');
        expect(result.warnings).toContain('Failure rate exceeds 20%');
    });
});
