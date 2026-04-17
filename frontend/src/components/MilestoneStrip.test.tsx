import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/projectionCalcs', () => ({
    findPeakBalance: vi.fn(),
    findDepletionYear: vi.fn(),
}));

import { findPeakBalance, findDepletionYear } from '../utils/projectionCalcs';
import MilestoneStrip from './MilestoneStrip';

function result(feasibility: unknown = null) {
    return { yearly_data: [], spending_feasibility: feasibility };
}

describe('MilestoneStrip', () => {
    it('shows "Funds last through plan" when no depletion and no feasibility profile', () => {
        vi.mocked(findPeakBalance).mockReturnValue({ balance: 1_500_000, year: 2040 });
        vi.mocked(findDepletionYear).mockReturnValue(null);
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<MilestoneStrip result={result() as any} retirementYear={2035} />);
        expect(screen.getByText('Funds last through plan')).toBeInTheDocument();
        expect(screen.getByText(/\$1,500,000/)).toBeInTheDocument();
        expect(screen.getByText('2035')).toBeInTheDocument();
    });

    it('shows depletion info when the portfolio depletes', () => {
        vi.mocked(findPeakBalance).mockReturnValue({ balance: 500_000, year: 2038 });
        vi.mocked(findDepletionYear).mockReturnValue({ year: 2055, age: 82 });
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<MilestoneStrip result={result() as any} retirementYear={2035} />);
        expect(screen.getByText(/2055 \(age 82\)/)).toBeInTheDocument();
    });

    it('shows "Fully Sustainable" when feasibility passes', () => {
        vi.mocked(findPeakBalance).mockReturnValue({ balance: 1_000_000, year: 2040 });
        vi.mocked(findDepletionYear).mockReturnValue(null);
        const feasibility = { spending_feasible: true, first_shortfall_age: null };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<MilestoneStrip result={result(feasibility) as any} retirementYear={2035} />);
        expect(screen.getByText('Fully Sustainable')).toBeInTheDocument();
    });

    it('shows "Underfunded" when feasibility fails', () => {
        vi.mocked(findPeakBalance).mockReturnValue({ balance: 1_000_000, year: 2040 });
        vi.mocked(findDepletionYear).mockReturnValue(null);
        const feasibility = { spending_feasible: false, first_shortfall_age: 78 };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<MilestoneStrip result={result(feasibility) as any} retirementYear={2035} />);
        expect(screen.getByText(/Underfunded at age 78/)).toBeInTheDocument();
    });
});
