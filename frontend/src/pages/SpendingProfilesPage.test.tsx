import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import SpendingProfilesPage from './SpendingProfilesPage';
import type { SpendingProfile, GuardrailProfileResponse } from '../types/projection';

const mockProfiles: SpendingProfile[] = [
    {
        id: '1',
        name: 'Conservative',
        essential_expenses: 40000,
        discretionary_expenses: 20000,
        spending_tiers: [],
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-01T00:00:00Z',
    },
    {
        id: '2',
        name: 'Tiered Retirement',
        essential_expenses: 50000,
        discretionary_expenses: 30000,
        spending_tiers: [
            { name: 'Go-Go', start_age: 62, end_age: 70, essential_expenses: 156000, discretionary_expenses: 60000 },
            { name: 'Glide', start_age: 80, end_age: null, essential_expenses: 250000, discretionary_expenses: 118000 },
        ],
        created_at: '2024-02-01T00:00:00Z',
        updated_at: '2024-02-01T00:00:00Z',
    },
];

const mockGuardrailProfile: GuardrailProfileResponse = {
    id: 'gp-1',
    scenario_id: 'sc-1',
    name: 'Optimized Plan',
    essential_floor: 30000,
    terminal_balance_target: 0,
    return_mean: 0.10,
    return_stddev: 0.15,
    trial_count: 5000,
    confidence_level: 0.80,
    phases: [
        { name: 'Early', start_age: 62, end_age: 72, priority_weight: 3, target_spending: 80000 },
    ],
    yearly_spending: [
        { year: 2030, age: 62, recommended: 75000, corridor_low: 62000, corridor_high: 91000, essential_floor: 30000, discretionary: 45000, income_offset: 0, portfolio_withdrawal: 75000, phase_name: 'Early', portfolio_balance_median: 480000, portfolio_balance_p10: 200000, portfolio_balance_p25: 350000, portfolio_balance_p75: 650000 },
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
    cash_reserve_years: 2,
    cash_return_rate: 0.04,
};

vi.mock('../api/spendingProfiles', () => ({
    listSpendingProfiles: vi.fn(),
    createSpendingProfile: vi.fn(),
    updateSpendingProfile: vi.fn(),
    deleteSpendingProfile: vi.fn(),
}));

vi.mock('../api/projections', () => ({
    listScenarios: vi.fn(),
    getGuardrailProfile: vi.fn(),
    deleteGuardrailProfile: vi.fn(),
    reoptimize: vi.fn(),
}));

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

import { useApiQuery } from '../hooks/useApiQuery';
import { listScenarios, getGuardrailProfile } from '../api/projections';

const mockUseApiQuery = vi.mocked(useApiQuery);
const mockListScenarios = vi.mocked(listScenarios);
const mockGetGuardrailProfile = vi.mocked(getGuardrailProfile);

describe('SpendingProfilesPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mockListScenarios.mockResolvedValue([]);
        mockGetGuardrailProfile.mockResolvedValue(null);
    });

    it('renders profile cards with names and amounts', () => {
        mockUseApiQuery.mockReturnValue({ data: mockProfiles, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        expect(screen.getByText('Conservative')).toBeInTheDocument();
        expect(screen.getByText('Tiered Retirement')).toBeInTheDocument();
    });

    it('shows empty state when no profiles exist', () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        expect(screen.getByText(/no spending profiles yet/i)).toBeInTheDocument();
    });

    it('shows create form on New Profile click', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        expect(screen.getByRole('heading', { name: 'Create Profile' })).toBeInTheDocument();
    });

    it('cancel button hides the form', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        expect(screen.getByRole('heading', { name: 'Create Profile' })).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
        expect(screen.queryByRole('heading', { name: 'Create Profile' })).not.toBeInTheDocument();
    });

    it('renders tier summary on profile card', () => {
        mockUseApiQuery.mockReturnValue({ data: mockProfiles, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        expect(screen.getByText(/Go-Go/)).toBeInTheDocument();
        expect(screen.getByText(/62-70/)).toBeInTheDocument();
    });

    it('Add Spending Tier button adds a tier row', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        await userEvent.click(screen.getByRole('button', { name: /add spending tier/i }));

        expect(screen.getByText('Phase Name')).toBeInTheDocument();
        expect(screen.getByPlaceholderText('e.g., Go-Go Years')).toBeInTheDocument();
    });

    it('Remove button removes a tier row', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        await userEvent.click(screen.getByRole('button', { name: /add spending tier/i }));
        expect(screen.getByPlaceholderText('e.g., Go-Go Years')).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: /remove/i }));
        expect(screen.queryByPlaceholderText('e.g., Go-Go Years')).not.toBeInTheDocument();
    });

    it('loading state shows loading indicator', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() });
        renderWithRouter(<SpendingProfilesPage />);

        expect(screen.getByText('Loading...')).toBeInTheDocument();
    });

    it('shows guardrail profiles section when profiles exist', async () => {
        mockUseApiQuery.mockReturnValue({ data: mockProfiles, loading: false, error: null, refetch: vi.fn() });
        mockListScenarios.mockResolvedValue([{ id: 'sc-1', name: 'Test Scenario', retirement_date: '2030-01-01', end_age: 90, inflation_rate: 0.03, params_json: null, accounts: [], spending_profile: null, guardrail_profile: null, income_sources: [], created_at: '2024-01-01T00:00:00Z', updated_at: '2024-01-01T00:00:00Z' }]);
        mockGetGuardrailProfile.mockResolvedValue(mockGuardrailProfile);

        renderWithRouter(<SpendingProfilesPage />);

        await waitFor(() => {
            expect(screen.getByText('Monte Carlo Guardrail Profiles')).toBeInTheDocument();
        });
        expect(screen.getByText('Optimized Plan')).toBeInTheDocument();
        expect(screen.getByText(/Test Scenario/)).toBeInTheDocument();
    });
});
