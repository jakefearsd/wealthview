import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderWithRouter } from '../test-utils';
import ProjectionsPage from './ProjectionsPage';
import { makeScenario } from '../testutil/builders';

const mockScenarios = [
    makeScenario({ id: '1', name: 'Early Retirement' }),
    makeScenario({
        id: '2',
        name: 'Conservative Plan',
        retirement_date: '2050-06-15',
        end_age: 85,
        inflation_rate: 0.025,
        created_at: '2024-02-01T00:00:00Z',
        updated_at: '2024-02-01T00:00:00Z',
    }),
];

const { toastSuccess, toastError } = vi.hoisted(() => ({
    toastSuccess: vi.fn(), toastError: vi.fn(),
}));
vi.mock('react-hot-toast', () => ({
    default: { success: toastSuccess, error: toastError },
}));

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
import { deleteScenario } from '../api/projections';
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

        expect(screen.getByText('No scenarios')).toBeInTheDocument();
        expect(screen.getByText('Create one to get started.')).toBeInTheDocument();
    });

    it('shows create form on button click', async () => {
        mockUseApiQuery.mockReturnValue({ data: [], loading: false, error: null, refetch: vi.fn() });
        renderWithRouter(<ProjectionsPage />);

        await userEvent.click(screen.getByRole('button', { name: /new scenario/i }));
        expect(screen.getByRole('heading', { name: 'Create Scenario' })).toBeInTheDocument();
    });

    // === delete, and the form toggle ===
    //
    // Deleting a scenario takes its guardrail profile and projection history with it, and — unlike
    // every other destructive action in the app — this one has NO confirmation dialog. That is
    // worth pinning explicitly so a later refactor cannot quietly remove a guard that was never
    // there, and so the absence is visible to anyone reading the tests.

    const renderList = (scenarios = mockScenarios) => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: scenarios, loading: false, error: null, refetch: vi.fn() } as any);
        renderWithRouter(<ProjectionsPage />);
    };

    const cardFor = (name: string) => screen.getByText(name).closest('div')!.parentElement!;

    it('deletes the scenario whose row the control belongs to', async () => {
        vi.mocked(deleteScenario).mockResolvedValue(undefined as never);
        renderList();

        await userEvent.click(within(cardFor('Conservative Plan')).getByRole('button', { name: 'Delete' }));

        await waitFor(() => expect(deleteScenario).toHaveBeenCalledWith('2'));
        await waitFor(() => expect(toastSuccess).toHaveBeenCalledWith('Scenario deleted'));
    });

    it('deletes without asking for confirmation', async () => {
        // Documents current behaviour: unlike accounts, properties and guardrail profiles, a
        // scenario delete is immediate. If a confirm is ever added, this test should be the one
        // that fails and gets updated deliberately.
        const confirmSpy = vi.spyOn(window, 'confirm');
        vi.mocked(deleteScenario).mockResolvedValue(undefined as never);
        renderList();

        await userEvent.click(within(cardFor('Early Retirement')).getByRole('button', { name: 'Delete' }));

        await waitFor(() => expect(deleteScenario).toHaveBeenCalled());
        expect(confirmSpy).not.toHaveBeenCalled();
        confirmSpy.mockRestore();
    });

    it('keeps the scenario listed when the delete fails', async () => {
        vi.mocked(deleteScenario).mockRejectedValue(new Error('referenced by a projection'));
        renderList();

        await userEvent.click(within(cardFor('Early Retirement')).getByRole('button', { name: 'Delete' }));

        await waitFor(() => expect(toastError).toHaveBeenCalled());
        expect(screen.getByText('Early Retirement')).toBeInTheDocument();
    });

    it('toggles the create form closed again from the same control', async () => {
        // Rendered with no scenarios on purpose: a single mockReturnValue answers EVERY
        // useApiQuery call, including the ones ScenarioForm's nested sections make, so a non-empty
        // list would be handed to the income-sources section as if it were income sources.
        renderList([]);

        await userEvent.click(screen.getByRole('button', { name: 'New Scenario' }));
        expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();

        await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));

        expect(screen.getByRole('button', { name: 'New Scenario' })).toBeInTheDocument();
    });

    it('shows a loading state while scenarios are in flight', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        mockUseApiQuery.mockReturnValue({ data: null, loading: true, error: null, refetch: vi.fn() } as any);
        renderWithRouter(<ProjectionsPage />);

        expect(screen.getByText(/Loading scenarios/i)).toBeInTheDocument();
    });
});
