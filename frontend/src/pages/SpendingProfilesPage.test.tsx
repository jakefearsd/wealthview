import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import SpendingProfilesPage from './SpendingProfilesPage';
import type { SpendingProfile } from '../types/projection';

const mockProfiles: SpendingProfile[] = [
    {
        id: '1',
        name: 'Conservative',
        essential_expenses: 40000,
        discretionary_expenses: 20000,
        income_streams: [
            { name: 'Social Security', annual_amount: 24000, start_age: 67, end_age: null, inflation_rate: 0.02 },
        ],
        spending_tiers: [],
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-01T00:00:00Z',
    },
    {
        id: '2',
        name: 'Tiered Retirement',
        essential_expenses: 50000,
        discretionary_expenses: 30000,
        income_streams: [],
        spending_tiers: [
            { name: 'Go-Go', start_age: 62, end_age: 70, essential_expenses: 156000, discretionary_expenses: 60000 },
            { name: 'Glide', start_age: 80, end_age: null, essential_expenses: 250000, discretionary_expenses: 118000 },
        ],
        created_at: '2024-02-01T00:00:00Z',
        updated_at: '2024-02-01T00:00:00Z',
    },
];

vi.mock('../api/spendingProfiles', () => ({
    listSpendingProfiles: vi.fn(),
    createSpendingProfile: vi.fn(),
    updateSpendingProfile: vi.fn(),
    deleteSpendingProfile: vi.fn(),
}));

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

import { useApiQuery } from '../hooks/useApiQuery';
const mockUseApiQuery = vi.mocked(useApiQuery);

describe('SpendingProfilesPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders profile cards with names and amounts', () => {
        mockUseApiQuery.mockReturnValue({ data: mockProfiles, loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        expect(screen.getByText('Conservative')).toBeInTheDocument();
        expect(screen.getByText('Tiered Retirement')).toBeInTheDocument();
    });

    it('shows empty state when no profiles exist', () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        expect(screen.getByText(/no spending profiles yet/i)).toBeInTheDocument();
    });

    it('shows create form on New Profile click', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        expect(screen.getByRole('heading', { name: 'Create Profile' })).toBeInTheDocument();
    });

    it('cancel button hides the form', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        expect(screen.getByRole('heading', { name: 'Create Profile' })).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
        expect(screen.queryByRole('heading', { name: 'Create Profile' })).not.toBeInTheDocument();
    });

    it('renders tier summary on profile card', () => {
        mockUseApiQuery.mockReturnValue({ data: mockProfiles, loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        expect(screen.getByText(/Go-Go/)).toBeInTheDocument();
        expect(screen.getByText(/62-70/)).toBeInTheDocument();
    });

    it('renders income stream summary on profile card', () => {
        mockUseApiQuery.mockReturnValue({ data: mockProfiles, loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        expect(screen.getByText(/Social Security/)).toBeInTheDocument();
    });

    it('Add Spending Tier button adds a tier row', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        await userEvent.click(screen.getByRole('button', { name: /add spending tier/i }));

        expect(screen.getByText('Phase Name')).toBeInTheDocument();
        expect(screen.getByPlaceholderText('e.g., Go-Go Years')).toBeInTheDocument();
    });

    it('Remove button removes a tier row', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        await userEvent.click(screen.getByRole('button', { name: /new profile/i }));
        await userEvent.click(screen.getByRole('button', { name: /add spending tier/i }));
        expect(screen.getByPlaceholderText('e.g., Go-Go Years')).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: /remove/i }));
        expect(screen.queryByPlaceholderText('e.g., Go-Go Years')).not.toBeInTheDocument();
    });

    it('loading state shows loading indicator', () => {
        mockUseApiQuery.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() });
        render(<MemoryRouter><SpendingProfilesPage /></MemoryRouter>);

        expect(screen.getByText('Loading...')).toBeInTheDocument();
    });
});
