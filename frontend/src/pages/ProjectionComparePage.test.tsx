import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { BrowserRouter } from 'react-router';
import ProjectionComparePage from './ProjectionComparePage';

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: () => ({
        data: [
            { id: '1', name: 'Plan A' },
            { id: '2', name: 'Plan B' },
        ],
        loading: false,
        refetch: vi.fn(),
    }),
}));

vi.mock('../api/projections', () => ({
    listScenarios: vi.fn(),
    compareScenarios: vi.fn(),
}));

describe('ProjectionComparePage', () => {
    it('renders dropdowns and compare button', () => {
        render(
            <BrowserRouter>
                <ProjectionComparePage />
            </BrowserRouter>
        );

        expect(screen.getByText('Compare Scenarios')).toBeInTheDocument();
        expect(screen.getByText('Compare')).toBeInTheDocument();
        expect(screen.getAllByText('-- Select --')).toHaveLength(3);
    });
});
