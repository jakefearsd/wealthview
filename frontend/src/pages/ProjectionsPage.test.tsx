import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import ProjectionsPage from './ProjectionsPage';
import type { Scenario } from '../types/projection';

const mockScenarios: Scenario[] = [
    {
        id: '1',
        name: 'Early Retirement',
        retirement_date: '2045-01-01',
        end_age: 90,
        inflation_rate: 0.03,
        params_json: null,
        accounts: [],
        spending_profile: null,
        created_at: '2024-01-01T00:00:00Z',
        updated_at: '2024-01-01T00:00:00Z',
    },
    {
        id: '2',
        name: 'Conservative Plan',
        retirement_date: '2050-06-15',
        end_age: 85,
        inflation_rate: 0.025,
        params_json: null,
        accounts: [],
        spending_profile: null,
        created_at: '2024-02-01T00:00:00Z',
        updated_at: '2024-02-01T00:00:00Z',
    },
];

vi.mock('../api/projections', () => ({
    listScenarios: vi.fn(),
    createScenario: vi.fn(),
    deleteScenario: vi.fn(),
}));

vi.mock('../api/spendingProfiles', () => ({
    listSpendingProfiles: vi.fn().mockResolvedValue([]),
}));

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: vi.fn(),
}));

import { useApiQuery } from '../hooks/useApiQuery';
const mockUseApiQuery = vi.mocked(useApiQuery);

describe('ProjectionsPage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
    });

    it('renders scenario cards with names as links', () => {
        mockUseApiQuery.mockReturnValue({ data: mockScenarios, loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<ProjectionsPage />);

        const link1 = screen.getByRole('link', { name: 'Early Retirement' });
        expect(link1).toHaveAttribute('href', '/projections/1');

        const link2 = screen.getByRole('link', { name: 'Conservative Plan' });
        expect(link2).toHaveAttribute('href', '/projections/2');
    });

    it('shows empty state when no scenarios', () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<ProjectionsPage />);

        expect(screen.getByText(/no scenarios yet/i)).toBeInTheDocument();
    });

    it('shows create form on button click', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<ProjectionsPage />);

        await userEvent.click(screen.getByRole('button', { name: /new scenario/i }));
        expect(screen.getByRole('heading', { name: 'Create Scenario' })).toBeInTheDocument();
    });
});
