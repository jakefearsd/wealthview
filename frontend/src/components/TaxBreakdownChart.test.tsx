import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import TaxBreakdownChart from './TaxBreakdownChart';
import type { ProjectionYear } from '../types/projection';

vi.mock('recharts');

/** Reads the shaped rows the component handed to ComposedChart via the shared recharts mock. */
function chartData(): Array<Record<string, unknown>> {
    const raw = screen.getByTestId('composed-chart').getAttribute('data-chart-data');
    return raw ? JSON.parse(raw) : [];
}

function makeYear(overrides: Partial<ProjectionYear> & { year: number; age: number }): ProjectionYear {
    return {
        start_balance: 1000000,
        contributions: 0,
        growth: 50000,
        withdrawals: 40000,
        end_balance: 1010000,
        retired: true,
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
        self_employment_tax: null,
        rental_property_details: null,
        income_by_source: null,
        property_equity: null,
        total_net_worth: null,
        surplus_reinvested: null,
        taxable_growth: null,
        traditional_growth: null,
        roth_growth: null,
        tax_paid_from_taxable: null,
        tax_paid_from_traditional: null,
        tax_paid_from_roth: null,
        withdrawal_from_taxable: null,
        withdrawal_from_traditional: null,
        withdrawal_from_roth: null,
        federal_tax: null,
        state_tax: null,
        salt_deduction: null,
        used_itemized_deduction: null,
        rmd_amount: null,
        capital_gains_tax: null,
        irmaa_surcharge: null,
        early_withdrawal_penalty: null,
        ...overrides,
    };
}

describe('TaxBreakdownChart', () => {
    it('renders with federal-only tax data', () => {
        const data = [
            makeYear({ year: 2040, age: 65, tax_liability: 8000, federal_tax: 8000 }),
            makeYear({ year: 2041, age: 66, tax_liability: 9000, federal_tax: 9000 }),
        ];

        render(<TaxBreakdownChart data={data} retirementYear={2040} hasStateTax={false} />);
        expect(screen.getByTestId('responsive-container')).toBeDefined();
        expect(screen.getByText('Federal (Ordinary)')).toBeDefined();
        expect(screen.getByText('Effective Rate')).toBeDefined();
        expect(screen.queryByText('State Tax')).toBeNull();
    });

    it('renders with state tax data', () => {
        const data = [
            makeYear({
                year: 2040, age: 65,
                tax_liability: 12000, federal_tax: 8000, state_tax: 4000,
                salt_deduction: 10000, used_itemized_deduction: true,
            }),
        ];

        render(<TaxBreakdownChart data={data} retirementYear={2040} hasStateTax={true} />);
        expect(screen.getByText('Federal (Ordinary)')).toBeDefined();
        expect(screen.getByText('State Tax')).toBeDefined();
    });

    it('filters to retired years only', () => {
        const data = [
            makeYear({ year: 2039, age: 64, retired: false, tax_liability: 5000, federal_tax: 5000 }),
            makeYear({ year: 2040, age: 65, retired: true, tax_liability: 8000, federal_tax: 8000 }),
        ];

        render(<TaxBreakdownChart data={data} retirementYear={2040} hasStateTax={false} />);
        expect(screen.getByTestId('responsive-container')).toBeDefined();
    });

    it('shows SE Tax bar when self-employment tax is present', () => {
        const data = [
            makeYear({
                year: 2040, age: 65,
                tax_liability: 10000, federal_tax: 8000, self_employment_tax: 2000,
            }),
        ];

        render(<TaxBreakdownChart data={data} retirementYear={2040} hasStateTax={false} />);
        expect(screen.getByText('SE Tax')).toBeDefined();
    });

    it('shows the Cap-Gains Tax bar and splits it out of the federal bar without double-counting', () => {
        const data = [
            makeYear({
                year: 2040, age: 72,
                tax_liability: 10000, federal_tax: 8000, state_tax: 0, capital_gains_tax: 1500,
            }),
        ];

        render(<TaxBreakdownChart data={data} retirementYear={2040} hasStateTax={true} />);

        expect(screen.getByText('Cap-Gains Tax')).toBeDefined();
        expect(screen.getByText('Federal (Ordinary)')).toBeDefined();

        const point = chartData()[0] as { federal_tax: number; capital_gains_tax: number; state_tax: number };
        expect(point.capital_gains_tax).toBe(1500);
        expect(point.federal_tax).toBe(8000 - 1500);
        // Stack total (federal + cap-gains + state) must still equal the original federal_tax,
        // since capital_gains_tax is a breakout already included inside federal_tax, not additive.
        expect(point.federal_tax + point.capital_gains_tax + point.state_tax).toBe(8000);
    });

    it('hides the Cap-Gains Tax bar when no retired year has capital-gains tax', () => {
        const data = [
            makeYear({ year: 2040, age: 65, tax_liability: 8000, federal_tax: 8000 }),
        ];

        render(<TaxBreakdownChart data={data} retirementYear={2040} hasStateTax={false} />);

        expect(screen.queryByText('Cap-Gains Tax')).toBeNull();
    });
});
