import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BrowserRouter } from 'react-router';
import ProjectionComparePage from './ProjectionComparePage';
import type { CompareResponse, ProjectionResult, ProjectionYear, Scenario } from '../types/projection';

const hoisted = vi.hoisted(() => ({
    toastError: vi.fn(),
    useApiQuery: vi.fn(),
}));
const { toastError, useApiQuery: mockUseApiQuery } = hoisted;

vi.mock('react-hot-toast', () => ({
    default: { success: vi.fn(), error: hoisted.toastError },
}));

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: () => hoisted.useApiQuery(),
}));

vi.mock('../api/projections', () => ({
    listScenarios: vi.fn(),
    compareScenarios: vi.fn(),
}));

import { compareScenarios } from '../api/projections';
const mockCompare = vi.mocked(compareScenarios);

const SCENARIOS = [
    { id: '1', name: 'Plan A' },
    { id: '2', name: 'Plan B' },
] as unknown as Scenario[];

function year(y: number, age: number, endBalance: number): ProjectionYear {
    return { year: y, age, end_balance: endBalance } as unknown as ProjectionYear;
}

function result(scenarioId: string, years: ProjectionYear[], final: number): ProjectionResult {
    return {
        scenario_id: scenarioId,
        yearly_data: years,
        final_balance: final,
        years_in_retirement: 20,
        spending_feasibility: null,
    };
}

const COMPARE_RESPONSE: CompareResponse = {
    results: [
        result('1', [year(2040, 65, 800000), year(2041, 66, 900000)], 900000),
        result('2', [year(2040, 65, 500000), year(2041, 66, 0)], 0),
    ],
};

function renderPage() {
    return render(
        <BrowserRouter>
            <ProjectionComparePage />
        </BrowserRouter>
    );
}

describe('ProjectionComparePage', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        mockUseApiQuery.mockReturnValue({ data: SCENARIOS, loading: false, refetch: vi.fn() });
    });

    it('shows a loading state while scenarios load', () => {
        mockUseApiQuery.mockReturnValue({ data: undefined, loading: true, refetch: vi.fn() });
        renderPage();

        expect(screen.getByText('Loading scenarios...')).toBeInTheDocument();
    });

    it('renders dropdowns and compare button', () => {
        renderPage();

        expect(screen.getByText('Compare Scenarios')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Compare' })).toBeInTheDocument();
        expect(screen.getAllByText('-- Select --')).toHaveLength(3);
    });

    it('rejects a comparison with fewer than two scenarios selected', async () => {
        renderPage();
        const user = userEvent.setup();

        await user.click(screen.getByRole('button', { name: 'Compare' }));

        expect(toastError).toHaveBeenCalledWith('Select at least 2 scenarios to compare');
        expect(mockCompare).not.toHaveBeenCalled();
    });

    it('compares the selected scenarios and renders the summary table', async () => {
        mockCompare.mockResolvedValue(COMPARE_RESPONSE);
        renderPage();
        const user = userEvent.setup();

        const selects = screen.getAllByRole('combobox');
        await user.selectOptions(selects[0], '1');
        await user.selectOptions(selects[1], '2');
        await user.click(screen.getByRole('button', { name: 'Compare' }));

        expect(await screen.findByText('Summary')).toBeInTheDocument();
        expect(mockCompare).toHaveBeenCalledWith(['1', '2']);
        // Plan A never depletes; Plan B hits $0 in 2041.
        expect(screen.getByText('Never')).toBeInTheDocument();
        expect(screen.getByText('2041 (age 66)')).toBeInTheDocument();
        // Peak balance row reflects the highest value reached for Plan A.
        expect(screen.getByText(/\$900,000\.00 \(2041\)/)).toBeInTheDocument();
    });

    it('surfaces a toast error when the comparison request fails', async () => {
        mockCompare.mockRejectedValue({
            isAxiosError: true,
            response: { data: { message: 'Compare failed' } },
        });
        renderPage();
        const user = userEvent.setup();

        const selects = screen.getAllByRole('combobox');
        await user.selectOptions(selects[0], '1');
        await user.selectOptions(selects[1], '2');
        await user.click(screen.getByRole('button', { name: 'Compare' }));

        await vi.waitFor(() => expect(toastError).toHaveBeenCalledWith('Compare failed'));
        expect(screen.queryByText('Summary')).not.toBeInTheDocument();
    });
});
