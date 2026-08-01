import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ProjectionYear } from '../types/projection';

vi.mock('recharts');

import BalanceChart from './BalanceChart';

/**
 * BalanceChart renders the primary projection balance visualisation, and had ZERO coverage: its
 * only consumer, ProjectionChart, mocks it out, so 238 lines never executed under test. It was
 * also invisible in the coverage report until `coverage.include` was configured, because a file
 * no test imports is absent from the denominator rather than reported as 0%.
 *
 * The logic worth pinning is the time-range selector. Which ranges appear depends on how much data
 * there is and where retirement falls, and picking one re-filters the series handed to recharts —
 * so an off-by-one in a threshold silently offers a range that shows the wrong span of a
 * retirement projection.
 */

function year(y: number, overrides: Partial<ProjectionYear> = {}): ProjectionYear {
    return {
        year: y,
        age: y - 1990,
        start_balance: 100000,
        contributions: 12000,
        growth: 7000,
        withdrawals: 0,
        end_balance: 119000,
        retired: false,
        traditional_balance: null,
        roth_balance: null,
        taxable_balance: null,
        roth_conversion_amount: null,
        tax_liability: null,
        essential_expenses: null,
        discretionary_expenses: null,
        income_streams_total: null,
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

const series = (count: number, from = 2030) =>
    Array.from({ length: count }, (_, i) => year(from + i));

/** Distinct years present in the data recharts was handed. */
function chartedYears(): number[] {
    const raw = screen.getByTestId('area-chart').getAttribute('data-chart-data');
    const rows = JSON.parse(raw ?? '[]') as Array<{ year: number }>;
    return [...new Set(rows.map((r) => r.year))].sort((a, b) => a - b);
}

describe('BalanceChart', () => {
    it('renders an area chart for a short series', () => {
        render(<BalanceChart data={series(5)} retirementYear={null} />);

        expect(screen.getByTestId('area-chart')).toBeInTheDocument();
        expect(chartedYears()).toEqual([2030, 2031, 2032, 2033, 2034]);
    });

    it('renders nothing chart-breaking for an empty series', () => {
        render(<BalanceChart data={[]} retirementYear={null} />);

        expect(screen.queryByText('All Years')).not.toBeInTheDocument();
    });

    // === which range options are offered ===

    it('offers no range selector when there is too little data to split', () => {
        // 7 rows: not > 7, so no "First 5" — and a lone "All Years" is suppressed entirely.
        render(<BalanceChart data={series(7)} retirementYear={null} />);

        expect(screen.queryByText('All Years')).not.toBeInTheDocument();
        expect(screen.queryByText('First 5')).not.toBeInTheDocument();
    });

    it('offers First 5 once the series exceeds seven years', () => {
        render(<BalanceChart data={series(8)} retirementYear={null} />);

        expect(screen.getByText('All Years')).toBeInTheDocument();
        expect(screen.getByText('First 5')).toBeInTheDocument();
        expect(screen.queryByText('First 10')).not.toBeInTheDocument();
    });

    it('adds longer ranges as the series grows past each threshold', () => {
        render(<BalanceChart data={series(23)} retirementYear={null} />);

        expect(screen.getByText('First 5')).toBeInTheDocument();
        expect(screen.getByText('First 10')).toBeInTheDocument();
        expect(screen.getByText('First 15')).toBeInTheDocument();
        expect(screen.getByText('First 20')).toBeInTheDocument();
    });

    it('offers no retirement-relative ranges when there is no retirement year', () => {
        render(<BalanceChart data={series(30)} retirementYear={null} />);

        expect(screen.queryByText('Retire + 5')).not.toBeInTheDocument();
    });

    it('offers retirement-relative ranges once enough retired years exist', () => {
        // 30 years from 2030; retiring in 2040 leaves 20 retired years -> +5, +10, +15 all offered.
        render(<BalanceChart data={series(30)} retirementYear={2040} />);

        expect(screen.getByText('Retire + 5')).toBeInTheDocument();
        expect(screen.getByText('Retire + 10')).toBeInTheDocument();
        expect(screen.getByText('Retire + 15')).toBeInTheDocument();
    });

    it('omits retirement ranges that the remaining retired years cannot fill', () => {
        // Retiring in 2052 leaves only 8 retired years: +5 qualifies (>7), +10 does not (not >12).
        render(<BalanceChart data={series(30)} retirementYear={2052} />);

        expect(screen.getByText('Retire + 5')).toBeInTheDocument();
        expect(screen.queryByText('Retire + 10')).not.toBeInTheDocument();
    });

    // === selecting a range re-filters the data ===

    it('narrows the charted years to the selected leading range', () => {
        render(<BalanceChart data={series(23)} retirementYear={null} />);
        expect(chartedYears()).toHaveLength(23);

        fireEvent.click(screen.getByText('First 5'));

        expect(chartedYears()).toEqual([2030, 2031, 2032, 2033, 2034]);
    });

    it('centres a retirement range on the year before retirement', () => {
        render(<BalanceChart data={series(30)} retirementYear={2040} />);

        fireEvent.click(screen.getByText('Retire + 5'));

        expect(chartedYears())
            .toEqual([2039, 2040, 2041, 2042, 2043, 2044]);
    });

    it('returns to the full series when All Years is reselected', () => {
        render(<BalanceChart data={series(23)} retirementYear={null} />);
        fireEvent.click(screen.getByText('First 5'));

        fireEvent.click(screen.getByText('All Years'));

        expect(chartedYears()).toHaveLength(23);
    });

    // === pool-aware rendering ===

    it('marks the retirement year with a labelled reference line', () => {
        render(<BalanceChart data={series(30)} retirementYear={2040} />);

        const labels = screen.getAllByTestId('reference-line').map((el) => el.getAttribute('data-label'));
        expect(labels).toContain('Retire');
    });

    it('shades the span from the spending-exceeds-growth crossover onwards', () => {
        // The crossover needs a retired year that has a spending profile and draws down more than
        // it grows. Without a spending profile there is no crossover and no shaded band at all.
        const withSpending = series(20).map((y, i) =>
            i >= 10
                ? { ...y, retired: true, essential_expenses: 60000, withdrawals: 60000, growth: 1000 }
                : y,
        );

        render(<BalanceChart data={withSpending} retirementYear={2040} />);

        expect(screen.getAllByTestId('reference-area')).not.toHaveLength(0);
        const labels = screen.getAllByTestId('reference-line').map((el) => el.getAttribute('data-label'));
        expect(labels.some((l) => l?.includes('Spending > Growth'))).toBe(true);
    });

    it('shades nothing when the projection never crosses over', () => {
        render(<BalanceChart data={series(20)} retirementYear={2040} />);

        expect(screen.queryByTestId('reference-area')).not.toBeInTheDocument();
    });

    it('charts the individual pools when pool balances are present', () => {
        const withPools = series(10).map((y) => ({
            ...y,
            traditional_balance: 50000,
            roth_balance: 25000,
            taxable_balance: 30000,
        }));

        render(<BalanceChart data={withPools} retirementYear={null} />);

        const keys = screen.getAllByTestId('area').map((el) => el.getAttribute('data-key'));
        expect(keys).toEqual(expect.arrayContaining(['traditional_balance', 'roth_balance']));
    });

    it('falls back to a cumulative-contributions series when no pool data exists', () => {
        render(<BalanceChart data={series(10)} retirementYear={null} />);

        const keys = screen.getAllByTestId('area').map((el) => el.getAttribute('data-key'));
        expect(keys).toContain('cumulative_contributions');
    });
});
