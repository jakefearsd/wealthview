import { render, screen, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ProjectionYear } from '../types/projection';

vi.mock('recharts');

import FlowsChart from './FlowsChart';

function year(y: number, overrides: Partial<ProjectionYear> = {}): ProjectionYear {
    return {
        year: y,
        age: y - 1990,
        start_balance: 500_000,
        contributions: 20_000,
        growth: 35_000,
        withdrawals: 0,
        end_balance: 555_000,
        retired: false,
        traditional_balance: null,
        roth_balance: null,
        taxable_balance: null,
        roth_conversion_amount: null,
        tax_liability: null,
        essential_expenses: null,
        discretionary_expenses: null,
        income_streams_total: 0,
        net_spending_need: null,
        spending_surplus: null,
        discretionary_after_cuts: null,
        rental_income_gross: null,
        rental_expenses_total: null,
        depreciation_total: null,
        rental_loss_applied: null,
        suspended_loss_carryforward: null,
        social_security_taxable: null,
        ...overrides,
    } as ProjectionYear;
}

const tooltip = () => within(screen.getByTestId('tooltip'));

/**
 * FlowsChart had zero coverage. Its logic lives in the tooltip: it re-derives a "Total Cash Flow"
 * line from withdrawals plus income streams and colours it red at exactly zero — the signal that a
 * retired year is funding nothing. Both the arithmetic and the suppression rule were untested.
 */
describe('FlowsChart', () => {
    it('charts the four flow series', () => {
        render(<FlowsChart data={[year(2030)]} retirementYear={null} />);

        const keys = screen.getAllByTestId('bar').map((el) => el.getAttribute('data-key'));
        expect(keys).toEqual(['contributions', 'growth', 'withdrawals', 'income_streams_total']);
    });

    it('passes the rows straight through without reshaping them', () => {
        render(<FlowsChart data={[year(2030), year(2031)]} retirementYear={null} />);

        const rows = JSON.parse(screen.getByTestId('bar-chart').getAttribute('data-chart-data') ?? '[]');
        expect(rows.map((r: { year: number }) => r.year)).toEqual([2030, 2031]);
    });

    // === retirement marker ===

    it('marks the retirement year when one is given', () => {
        render(<FlowsChart data={[year(2030)]} retirementYear={2045} />);

        expect(screen.getByTestId('reference-line')).toHaveAttribute('data-label', 'Retire');
    });

    it('omits the retirement marker when there is no retirement year', () => {
        render(<FlowsChart data={[year(2030)]} retirementYear={null} />);

        expect(screen.queryByTestId('reference-line')).not.toBeInTheDocument();
    });

    // === tooltip ===

    it('heads the tooltip with the year and that year\'s age', () => {
        render(<FlowsChart data={[year(2030)]} retirementYear={null} />);

        expect(tooltip().getByText('2030 (age 40)')).toBeInTheDocument();
    });

    it('totals withdrawals and income streams into a cash-flow line', () => {
        render(<FlowsChart
            data={[year(2050, { withdrawals: 40_000, income_streams_total: 25_000 })]}
            retirementYear={2050}
        />);

        expect(tooltip().getByText(/Total Cash Flow: \$65,000/)).toBeInTheDocument();
    });

    it('omits the cash-flow total in an accumulation year with neither withdrawals nor income', () => {
        render(<FlowsChart
            data={[year(2030, { withdrawals: 0, income_streams_total: 0 })]}
            retirementYear={null}
        />);

        expect(tooltip().queryByText(/Total Cash Flow/)).not.toBeInTheDocument();
    });

    it('still shows the total when income exists but nothing was withdrawn', () => {
        render(<FlowsChart
            data={[year(2050, { withdrawals: 0, income_streams_total: 30_000 })]}
            retirementYear={2050}
        />);

        expect(tooltip().getByText(/Total Cash Flow: \$30,000/)).toBeInTheDocument();
    });

    it('renames the raw series keys to their human-readable labels', () => {
        render(<FlowsChart data={[year(2030)]} retirementYear={null} />);

        const t = tooltip();
        expect(t.getByText(/^Contributions:/)).toBeInTheDocument();
        expect(t.getByText(/^Growth:/)).toBeInTheDocument();
    });
});
