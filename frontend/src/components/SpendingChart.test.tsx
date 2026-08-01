import { render, screen, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ProjectionYear } from '../types/projection';

vi.mock('recharts');

import SpendingChart from './SpendingChart';

function year(y: number, overrides: Partial<ProjectionYear> = {}): ProjectionYear {
    return {
        year: y,
        age: y - 1990,
        start_balance: 1_000_000,
        contributions: 0,
        growth: 40_000,
        withdrawals: 55_000,
        end_balance: 985_000,
        retired: true,
        traditional_balance: null,
        roth_balance: null,
        taxable_balance: null,
        roth_conversion_amount: null,
        tax_liability: null,
        essential_expenses: 40_000,
        discretionary_expenses: 20_000,
        income_streams_total: 15_000,
        net_spending_need: null,
        spending_surplus: null,
        discretionary_after_cuts: 20_000,
        rental_income_gross: null,
        rental_expenses_total: null,
        depreciation_total: null,
        rental_loss_applied: null,
        suspended_loss_carryforward: null,
        social_security_taxable: null,
        ...overrides,
    } as ProjectionYear;
}

function chartRows(): Array<Record<string, unknown>> {
    const raw = screen.getByTestId('area-chart').getAttribute('data-chart-data');
    return JSON.parse(raw ?? '[]');
}

/**
 * SpendingChart had zero coverage. Its logic is the `disc_cut_line` series: a dashed marker drawn
 * ONLY in years where guardrails actually cut discretionary spending. Getting the condition wrong
 * either hides a real cut from the user or draws a spurious "you were cut" line in a year where
 * nothing was reduced.
 */
describe('SpendingChart', () => {
    it('charts only the years that carry a spending profile', () => {
        render(<SpendingChart data={[
            year(2040, { essential_expenses: null }),
            year(2041),
            year(2042),
        ]} />);

        expect(chartRows().map((r) => r.year)).toEqual([2041, 2042]);
    });

    it('renders an empty chart when no year has a spending profile', () => {
        render(<SpendingChart data={[year(2040, { essential_expenses: null })]} />);

        expect(chartRows()).toEqual([]);
    });

    // === the discretionary-cut marker ===

    it('omits the cut line in a year where discretionary spending was not reduced', () => {
        render(<SpendingChart data={[
            year(2041, { discretionary_expenses: 20_000, discretionary_after_cuts: 20_000 }),
        ]} />);

        expect(chartRows()[0].disc_cut_line).toBeNull();
    });

    it('draws the cut line at essential plus the reduced discretionary when a cut occurred', () => {
        render(<SpendingChart data={[
            year(2041, {
                essential_expenses: 40_000,
                discretionary_expenses: 20_000,
                discretionary_after_cuts: 12_000,
            }),
        ]} />);

        expect(chartRows()[0].disc_cut_line)
            .toBe(52_000);
    });

    it('omits the cut line when the after-cuts figure is unknown', () => {
        render(<SpendingChart data={[
            year(2041, { discretionary_expenses: 20_000, discretionary_after_cuts: null }),
        ]} />);

        expect(chartRows()[0].disc_cut_line).toBeNull();
    });

    it('treats a missing essential figure as zero when positioning the cut line', () => {
        render(<SpendingChart data={[
            year(2041, {
                essential_expenses: 0,
                discretionary_expenses: 20_000,
                discretionary_after_cuts: 5_000,
            }),
        ]} />);

        expect(chartRows()[0].disc_cut_line).toBe(5_000);
    });

    // === series and tooltip ===

    it('stacks essential and post-cut discretionary, and overlays withdrawals and income', () => {
        render(<SpendingChart data={[year(2041)]} />);

        const keys = screen.getAllByTestId('area').map((el) => el.getAttribute('data-key'));
        expect(keys).toEqual([
            'essential_expenses', 'discretionary_after_cuts', 'withdrawals', 'income_streams_total',
        ]);
    });

    it('labels the tooltip with the year and the age for that year', () => {
        render(<SpendingChart data={[year(2041)]} />);

        expect(within(screen.getByTestId('tooltip')).getByTestId('tooltip-label'))
            .toHaveTextContent('2041 (age 51)');
    });

    it('formats tooltip values as currency under their human-readable series names', () => {
        render(<SpendingChart data={[year(2041)]} />);

        const entries = within(screen.getByTestId('tooltip')).getAllByTestId('tooltip-entry');
        const text = entries.map((e) => e.textContent);
        expect(text).toContain('Essential Expenses');
        expect(text).toContain('Discretionary (After Cuts)');
    });
});
