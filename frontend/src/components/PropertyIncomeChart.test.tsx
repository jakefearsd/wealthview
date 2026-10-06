import { screen, within } from '@testing-library/react';
import { renderWithRouter } from '../test-utils';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { MonthlyCashFlowDetailEntry, DepreciationScheduleResponse, Property } from '../types/property';
import { annualDebtService, monthlyPayment } from '../utils/amortization';

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
    // Needed since the shared recharts mock now renders Tooltip content, which reaches ChartTooltip.
    tooltipStyle: {},
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

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText(/No income or expense data logged/)).toBeInTheDocument();
            expect(screen.queryByTestId('bar-chart')).not.toBeInTheDocument();
        });

        it('links the empty-state hint to this property\'s detail page, not the list', () => {
            setQuery([]);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByRole('link', { name: 'property detail page' })).toHaveAttribute('href', '/properties/p1');
        });

        it('shows the loading placeholder while the property data is in flight', () => {
            setQuery(null, null, null, true);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText('Loading property data...')).toBeInTheDocument();
        });

        it('plots expenses below the zero line and rent above it', () => {
            setQuery([entry('2026-04', { mortgage: 1200, insurance: 150 })]);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

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

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

            expect(barNames()).toEqual([
                'Rent Estimate', 'Mortgage', 'Tax', 'CapEx', 'Net Cash Flow',
            ]);
        });

        it('totals income, expenses and net across every month shown', () => {
            setQuery([
                entry('2026-03', { mortgage: 1000 }),
                entry('2026-04', { mortgage: 1000, tax: 500 }),
            ]);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText('$4,400')).toBeInTheDocument();
            expect(screen.getByText('$2,500')).toBeInTheDocument();
            expect(screen.getByText('+$1,900')).toBeInTheDocument();
        });

        it('flags a negative net cash flow without a plus sign', () => {
            setQuery([entry('2026-04', { mortgage: 5000 })]);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);

            expect(screen.getByText('-$2,800')).toBeInTheDocument();
        });
    });

    describe('forward projection view', () => {
        it('compounds rent and expenses by the inflation rate each year', async () => {
            const user = userEvent.setup();
            setQuery([], null, { annual_property_tax: 1000 } as Partial<Property>);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={12000} inflationRate={0.1} />);
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

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);
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

            renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={12000} />);
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

            renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={12000} />);
            await user.click(screen.getByRole('button', { name: '10 Year' }));

            expect(screen.getByText('10yr Depreciation')).toBeInTheDocument();
            expect(barNames()).toContain('Depreciation');
            expect(screen.getByText(/non-cash tax deduction/)).toBeInTheDocument();
        });

        it('omits the depreciation tile, bar and disclaimer when the schedule is empty', async () => {
            const user = userEvent.setup();
            setQuery([], { schedule: [] } as unknown as DepreciationScheduleResponse, null);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={12000} />);
            await user.click(screen.getByRole('button', { name: '10 Year' }));

            expect(screen.queryByText('10yr Depreciation')).not.toBeInTheDocument();
            expect(barNames()).not.toContain('Depreciation');
            expect(screen.queryByText(/non-cash tax deduction/)).not.toBeInTheDocument();
        });

        describe('mortgage debt service', () => {
            // $360,000 at 4.25% for 30 years from July 2018: ~$1,771/mo of principal and interest.
            const mortgaged = {
                annual_property_tax: 3000,
                annual_insurance_cost: 1000,
                mortgage_balance: 300000,
                has_loan_details: true,
                loan_amount: 360000,
                annual_interest_rate: 0.0425,
                loan_term_months: 360,
                loan_start_date: '2018-07-01',
            } as Partial<Property>;
            const terms = { loanAmount: 360000, annualRate: 0.0425, termMonths: 360, startDate: '2018-07-01' };

            it('subtracts principal and interest from net cash flow and only interest from taxable income', async () => {
                const user = userEvent.setup();
                const schedule = {
                    schedule: [{ tax_year: 2026, annual_depreciation: 5000, cumulative_taken: 5000, remaining_basis: 0 }],
                } as DepreciationScheduleResponse;
                setQuery([], schedule, mortgaged);

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} />);
                await user.click(screen.getByRole('button', { name: '5 Year' }));

                const ds = annualDebtService(terms, 2026);
                expect(monthlyPayment(360000, 0.0425, 360)).toBeCloseTo(1771, 0);
                const row = chartRows()[0];
                expect(row.mortgage).toBeCloseTo(-ds.payment, 6);
                expect(row.netCash).toBeCloseTo(36000 - 4000 - ds.payment, 6);
                expect(row.netTaxable).toBeCloseTo(36000 - 4000 - ds.interest - 5000, 6);
            });

            it('holds the payment at its fixed nominal amount while other expenses inflate', async () => {
                const user = userEvent.setup();
                setQuery([], null, mortgaged);

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} inflationRate={0.1} />);
                await user.click(screen.getByRole('button', { name: '5 Year' }));

                const rows = chartRows();
                expect(rows[1].expenses).toBeCloseTo(-4000 * 1.1, 6);
                expect(rows[1].mortgage).toBeCloseTo(rows[0].mortgage as number, 6);
            });

            it('shows the mortgage as its own bar, tooltip line and part of the net cash flow tile', async () => {
                const user = userEvent.setup();
                setQuery([], null, mortgaged);

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} />);
                await user.click(screen.getByRole('button', { name: '5 Year' }));

                expect(barNames()).toEqual(['Rental Income', 'Operating Expenses', 'Mortgage (P&I)', 'Net Cash Flow']);
                const ds = annualDebtService(terms, 2026);
                const tooltip = within(screen.getByTestId('tooltip'));
                expect(tooltip.getByText(`Mortgage (P&I): $${Math.round(ds.payment).toLocaleString()}`)).toBeInTheDocument();
                const fiveYearNet = [2026, 2027, 2028, 2029, 2030]
                    .reduce((s, y) => s + 36000 - 4000 - annualDebtService(terms, y).payment, 0);
                expect(screen.getByText(`+$${Math.round(fiveYearNet).toLocaleString()}`)).toBeInTheDocument();
            });

            it('stops the mortgage line in the year the loan is paid off', async () => {
                const user = userEvent.setup();
                // A 20-year loan from January 2010 makes its last payment in January 2030.
                setQuery([], null, {
                    ...mortgaged, loan_amount: 200000, loan_term_months: 240, loan_start_date: '2010-01-01',
                });

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} />);
                await user.click(screen.getByRole('button', { name: '10 Year' }));

                const payment = monthlyPayment(200000, 0.0425, 240);
                const byYear = Object.fromEntries(chartRows().map(r => [r.year, r.mortgage as number]));
                expect(byYear[2029]).toBeCloseTo(-payment * 12, 6);
                expect(byYear[2030]).toBeCloseTo(-payment, 6);
                expect(byYear[2031]).toBe(0);
                expect(byYear[2035]).toBe(0);
            });

            it('charges principal but no interest on a zero-rate loan', async () => {
                const user = userEvent.setup();
                setQuery([], null, {
                    ...mortgaged, annual_interest_rate: 0, loan_amount: 120000, loan_term_months: 120, loan_start_date: '2020-01-01',
                });

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} />);
                await user.click(screen.getByRole('button', { name: '5 Year' }));

                expect(chartRows()[0]).toEqual(expect.objectContaining({
                    mortgage: -12000,
                    netCash: 36000 - 4000 - 12000,
                    netTaxable: 36000 - 4000,
                }));
            });

            it('adds no mortgage bar or note for a property without a loan', async () => {
                const user = userEvent.setup();
                setQuery([], null, { annual_property_tax: 3000, mortgage_balance: 0, has_loan_details: false } as Partial<Property>);

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} />);
                await user.click(screen.getByRole('button', { name: '5 Year' }));

                expect(chartRows()[0]).toEqual(expect.objectContaining({ mortgage: 0, netCash: 33000 }));
                expect(barNames()).not.toContain('Mortgage (P&I)');
                expect(screen.queryByText(/debt service is not included/i)).not.toBeInTheDocument();
            });

            it('notes that debt service is missing when a balance has no loan details to amortize', async () => {
                const user = userEvent.setup();
                setQuery([], null, { annual_property_tax: 3000, mortgage_balance: 250000, has_loan_details: false } as Partial<Property>);

                renderWithRouter(<PropertyIncomeChart {...defaultProps} annualRent={36000} />);
                await user.click(screen.getByRole('button', { name: '5 Year' }));

                expect(screen.getByText(/debt service is not included/i)).toBeInTheDocument();
                expect(screen.getByRole('link', { name: 'add loan details' })).toHaveAttribute('href', '/properties/p1');
                // No payment is guessed from the balance.
                expect(chartRows()[0]).toEqual(expect.objectContaining({ mortgage: 0, netCash: 33000 }));
            });
        });

        it('switches the heading and returns to the trailing view', async () => {
            const user = userEvent.setup();
            setQuery([entry('2026-04', { mortgage: 1200 })]);

            renderWithRouter(<PropertyIncomeChart {...defaultProps} />);
            expect(screen.getByText(/Rent vs Expenses/)).toBeInTheDocument();

            await user.click(screen.getByRole('button', { name: '20 Year' }));
            expect(screen.getByText(/Projected Cash Flow & Depreciation/)).toBeInTheDocument();
            expect(chartRows()).toHaveLength(20);

            await user.click(screen.getByRole('button', { name: 'Trailing 12 Mo' }));
            expect(screen.getByText(/Rent vs Expenses/)).toBeInTheDocument();
        });
    });
});
