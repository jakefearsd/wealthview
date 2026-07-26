import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { MonthlyCashFlowDetailEntry, DepreciationScheduleResponse, Property } from '../types/property';

const hoisted = vi.hoisted(() => ({
    query: { data: null as unknown, loading: false, error: null, refetch: () => {} },
}));

vi.mock('../hooks/useApiQuery', () => ({
    useApiQuery: () => hoisted.query,
}));

vi.mock('../api/properties', () => ({
    getCashFlowDetail: vi.fn(),
    getDepreciationSchedule: vi.fn(),
    getProperty: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    // Mirrors Intl currency formatting closely enough to assert on: sign outside the symbol.
    formatCurrency: (v: number) =>
        `${v < 0 ? '-' : ''}$${Math.abs(Math.round(v)).toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('recharts');

import PropertyIncomeChart from './PropertyIncomeChart';

function entry(month: string, byCategory: Record<string, number>): MonthlyCashFlowDetailEntry {
    const total = Object.values(byCategory).reduce((s, v) => s + v, 0);
    return {
        month,
        total_income: 0,
        expenses_by_category: byCategory,
        total_expenses: total,
        net_cash_flow: -total,
    };
}

function setQuery(
    trailing: MonthlyCashFlowDetailEntry[] | null,
    depSchedule: DepreciationScheduleResponse | null = null,
    property: Partial<Property> | null = null,
    loading = false,
) {
    hoisted.query = {
        data: loading ? null : [trailing, depSchedule, property],
        loading,
        error: null,
        refetch: () => {},
    };
}

function chartRows(): Record<string, number | string>[] {
    const raw = screen.getByTestId('bar-chart').getAttribute('data-chart-data');
    return JSON.parse(raw ?? '[]');
}

function barNames(): string[] {
    return screen.getAllByTestId('bar').map(b => b.getAttribute('data-name') ?? '');
}

const defaultProps = {
    propertyId: 'p1',
    propertyAddress: '2020 Beryl St',
    monthlyRentEstimate: 2200,
};

describe('PropertyIncomeChart', () => {
    beforeEach(() => {
        // Only Date is faked: the forward projection anchors on the current year, but faking
        // timers wholesale would starve userEvent's internal delays and hang every click.
        vi.useFakeTimers({ toFake: ['Date'] });
        vi.setSystemTime(new Date('2026-07-25T00:00:00Z'));
        setQuery([]);
    });

    afterEach(() => {
        vi.useRealTimers();
    });

    describe('trailing 12-month view', () => {
        it('shows a placeholder instead of a chart when no records are logged', () => {
            setQuery([]);

            render(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText(/No income or expense data logged/)).toBeInTheDocument();
            expect(screen.queryByTestId('bar-chart')).not.toBeInTheDocument();
        });

        it('shows the loading placeholder while the property data is in flight', () => {
            setQuery(null, null, null, true);

            render(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText('Loading property data...')).toBeInTheDocument();
        });

        it('plots expenses below the zero line and rent above it', () => {
            setQuery([entry('2026-04', { mortgage: 1200, insurance: 150 })]);

            render(<PropertyIncomeChart {...defaultProps} />);

            expect(chartRows()).toEqual([
                expect.objectContaining({
                    label: "Apr '26",
                    income: 2200,
                    mortgage: -1200,
                    insurance: -150,
                    net: 850,
                }),
            ]);
        });

        it('orders expense bars by category config, not by appearance in the data', () => {
            setQuery([entry('2026-04', { capex: 100, mortgage: 1200, tax: 300 })]);

            render(<PropertyIncomeChart {...defaultProps} />);

            expect(barNames()).toEqual([
                'Rent Estimate', 'Mortgage', 'Tax', 'CapEx', 'Net Cash Flow',
            ]);
        });

        it('totals income, expenses and net across every month shown', () => {
            setQuery([
                entry('2026-03', { mortgage: 1000 }),
                entry('2026-04', { mortgage: 1000, tax: 500 }),
            ]);

            render(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText('$4,400')).toBeInTheDocument();
            expect(screen.getByText('$2,500')).toBeInTheDocument();
            expect(screen.getByText('+$1,900')).toBeInTheDocument();
        });

        it('flags a negative net cash flow without a plus sign', () => {
            setQuery([entry('2026-04', { mortgage: 5000 })]);

            render(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText('-$2,800')).toBeInTheDocument();
        });
    });

    describe('forward projection view', () => {
        it('compounds rent and expenses by the inflation rate each year', async () => {
            const user = userEvent.setup();
            setQuery([], null, { annual_property_tax: 1000 } as Partial<Property>);

            render(<PropertyIncomeChart {...defaultProps} annualRent={12000} inflationRate={0.1} />);
            await user.click(screen.getByRole('button', { name: '5 Year' }));

            const rows = chartRows();
            expect(rows).toHaveLength(5);
            expect(rows[0]).toEqual(expect.objectContaining({
                label: '2026', year: 2026, income: 12000, expenses: -1000,
            }));
            // Year two is one full year of 10% inflation on both sides.
            expect(rows[1]).toEqual(expect.objectContaining({
                label: '2027',
                income: 12000 * 1.1,
                expenses: -(1000 * 1.1),
            }));
        });

        it('falls back to twelve times the monthly estimate when no annual rent is given', async () => {
            const user = userEvent.setup();
            setQuery([]);

            render(<PropertyIncomeChart {...defaultProps} />);
            await user.click(screen.getByRole('button', { name: '5 Year' }));

            expect(chartRows()[0]).toEqual(expect.objectContaining({ income: 2200 * 12 }));
        });

        it('subtracts depreciation from taxable income but not from cash flow', async () => {
            const user = userEvent.setup();
            const schedule = {
                schedule: [{
                    tax_year: 2026,
                    annual_depreciation: 5000,
                    cumulative_taken: 5000,
                    remaining_basis: 0,
                }],
            } as DepreciationScheduleResponse;
            setQuery([], schedule, { annual_property_tax: 2000 } as Partial<Property>);

            render(<PropertyIncomeChart {...defaultProps} annualRent={12000} />);
            await user.click(screen.getByRole('button', { name: '5 Year' }));

            const rows = chartRows();
            expect(rows[0]).toEqual(expect.objectContaining({
                depreciation: -5000,
                netCash: 10000,
                netTaxable: 5000,
            }));
            // No schedule entry for 2027, so that year carries no depreciation.
            expect(rows[1]).toEqual(expect.objectContaining({
                depreciation: 0,
                netCash: 10000,
                netTaxable: 10000,
            }));
        });

        it('adds the depreciation tile, bar and disclaimer only when a schedule exists', async () => {
            const user = userEvent.setup();
            const schedule = {
                schedule: [{
                    tax_year: 2026,
                    annual_depreciation: 5000,
                    cumulative_taken: 5000,
                    remaining_basis: 0,
                }],
            } as DepreciationScheduleResponse;
            setQuery([], schedule, null);

            render(<PropertyIncomeChart {...defaultProps} annualRent={12000} />);
            await user.click(screen.getByRole('button', { name: '10 Year' }));

            expect(screen.getByText('10yr Depreciation')).toBeInTheDocument();
            expect(barNames()).toContain('Depreciation');
            expect(screen.getByText(/non-cash tax deduction/)).toBeInTheDocument();
        });

        it('omits the depreciation tile, bar and disclaimer when the schedule is empty', async () => {
            const user = userEvent.setup();
            setQuery([], { schedule: [] } as unknown as DepreciationScheduleResponse, null);

            render(<PropertyIncomeChart {...defaultProps} annualRent={12000} />);
            await user.click(screen.getByRole('button', { name: '10 Year' }));

            expect(screen.queryByText('10yr Depreciation')).not.toBeInTheDocument();
            expect(barNames()).not.toContain('Depreciation');
            expect(screen.queryByText(/non-cash tax deduction/)).not.toBeInTheDocument();
        });

        it('switches the heading and returns to the trailing view', async () => {
            const user = userEvent.setup();
            setQuery([entry('2026-04', { mortgage: 1200 })]);

            render(<PropertyIncomeChart {...defaultProps} />);
            expect(screen.getByText(/Rent vs Expenses/)).toBeInTheDocument();

            await user.click(screen.getByRole('button', { name: '20 Year' }));
            expect(screen.getByText(/Projected Cash Flow & Depreciation/)).toBeInTheDocument();
            expect(chartRows()).toHaveLength(20);

            await user.click(screen.getByRole('button', { name: 'Trailing 12 Mo' }));
            expect(screen.getByText(/Rent vs Expenses/)).toBeInTheDocument();
        });
    });
});
