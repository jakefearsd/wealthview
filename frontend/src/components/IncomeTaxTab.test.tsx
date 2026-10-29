import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    tableStyle: {},
}));

vi.mock('./TaxBreakdownChart', () => ({
    default: () => <div data-testid="tax-breakdown-chart" />,
}));

import IncomeTaxTab from './IncomeTaxTab';
import type { ProjectionYear } from '../types/projection';

function makeYear(overrides: Partial<ProjectionYear> & { year: number; age: number }): ProjectionYear {
    return {
        start_balance: 1_000_000,
        contributions: 0,
        growth: 50000,
        withdrawals: 40000,
        end_balance: 1_010_000,
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

const commonProps = {
    retirementYear: 2040,
    expandedTaxYears: new Set<number>(),
    onToggleTaxYear: vi.fn(),
};

describe('IncomeTaxTab', () => {
    it('renders RMD and Cap-Gains Tax columns with formatted values when present', () => {
        const data = [
            makeYear({ year: 2040, age: 72, rmd_amount: 12000, capital_gains_tax: 1500, tax_liability: 9000 }),
        ];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} />);

        expect(screen.getByText('RMD')).toBeInTheDocument();
        expect(screen.getByText('Cap-Gains Tax')).toBeInTheDocument();
        expect(screen.getByText('$12,000')).toBeInTheDocument();
        expect(screen.getByText('$1,500')).toBeInTheDocument();
    });

    it('hides RMD and Cap-Gains Tax columns when no retired year has values', () => {
        const data = [
            makeYear({ year: 2040, age: 65, tax_liability: 5000 }),
        ];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} />);

        expect(screen.queryByText('RMD')).not.toBeInTheDocument();
        expect(screen.queryByText('Cap-Gains Tax')).not.toBeInTheDocument();
    });

    it('renders IRMAA and Early Penalty columns with formatted values when present', () => {
        const data = [
            makeYear({ year: 2040, age: 72, irmaa_surcharge: 2500, early_withdrawal_penalty: 800, tax_liability: 9000 }),
        ];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} />);

        expect(screen.getByText('IRMAA')).toBeInTheDocument();
        expect(screen.getByText('Early Penalty')).toBeInTheDocument();
        expect(screen.getByText('$2,500')).toBeInTheDocument();
        expect(screen.getByText('$800')).toBeInTheDocument();
    });

    it('hides IRMAA and Early Penalty columns when no year has values', () => {
        const data = [
            makeYear({ year: 2040, age: 65, tax_liability: 5000 }),
        ];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} />);

        expect(screen.queryByText('IRMAA')).not.toBeInTheDocument();
        expect(screen.queryByText('Early Penalty')).not.toBeInTheDocument();
    });

    it('reserves the same fixed-width marker slot on rows with and without property details', () => {
        const detail = {
            income_source_id: 'is-1', property_name: 'Rental', tax_treatment: 'rental_passive', gross_rent: 1000,
            operating_expenses: 0, mortgage_interest: 0, property_tax: 0, depreciation: 0,
            loss_applied_to_income: 0, suspended_loss_carryforward: 0, net_income: 1000,
        } as unknown as NonNullable<ProjectionYear['rental_property_details']>[number];
        const data = [
            makeYear({ year: 2040, age: 65, tax_liability: 5000, rental_property_details: [detail] }),
            makeYear({ year: 2041, age: 66, tax_liability: 5000 }),
        ];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} />);

        const marker = (year: string) => screen.getByText(year).querySelector('span') as HTMLElement;
        expect(marker('2040').style.width).toBe('1.25em');
        expect(marker('2041').style.width).toBe('1.25em');
        expect(marker('2041').textContent).toBe('');
    });

    it('shows the federal and state breakdown with an Itemized or Standard badge per year', () => {
        const data = [
            makeYear({
                year: 2040, age: 65, tax_liability: 9000, federal_tax: 6000, state_tax: 3000,
                salt_deduction: 10000, used_itemized_deduction: true,
            }),
            makeYear({
                year: 2041, age: 66, tax_liability: 8000, federal_tax: 5500, state_tax: 2500,
                salt_deduction: 9000, used_itemized_deduction: false,
            }),
            makeYear({ year: 2042, age: 67, tax_liability: 7000 }),
        ];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} />);

        expect(screen.getByText('Tax Breakdown: Federal + State')).toBeInTheDocument();
        const row = (year: string) => screen.getByText(year).closest('tr') as HTMLElement;
        expect(row('2040')).toHaveTextContent('Itemized');
        expect(row('2040')).toHaveTextContent('$6,000');
        expect(row('2040')).toHaveTextContent('$3,000');
        expect(row('2041')).toHaveTextContent('Standard');
        expect(row('2041')).toHaveTextContent('$5,500');
        expect(row('2041')).toHaveTextContent('$2,500');
        expect(row('2042')).not.toHaveTextContent(/Itemized|Standard/);
    });

    it('titles the table "Tax Burden Over Time" when no year has a state tax', () => {
        render(<IncomeTaxTab yearlyData={[makeYear({ year: 2040, age: 65, tax_liability: 5000 })]} {...commonProps} />);

        expect(screen.getByText('Tax Burden Over Time')).toBeInTheDocument();
    });

    it('toggles a year only when it has rental property details', () => {
        const onToggle = vi.fn();
        const detail = {
            income_source_id: 'is-1', property_name: 'Rental', tax_treatment: 'rental_passive', gross_rent: 1000,
            operating_expenses: 0, mortgage_interest: 0, property_tax: 0, depreciation: 0,
            loss_applied_to_income: 0, suspended_loss_carryforward: 0, net_income: 1000,
        } as unknown as NonNullable<ProjectionYear['rental_property_details']>[number];
        const data = [
            makeYear({ year: 2040, age: 65, tax_liability: 5000, rental_property_details: [detail] }),
            makeYear({ year: 2041, age: 66, tax_liability: 5000 }),
        ];
        render(<IncomeTaxTab yearlyData={data} {...commonProps} onToggleTaxYear={onToggle} />);

        fireEvent.click(screen.getByText('2041'));
        fireEvent.click(screen.getByText('2040'));

        expect(onToggle).toHaveBeenCalledTimes(1);
        expect(onToggle).toHaveBeenCalledWith(2040);
    });

    it.each([
        ['rental_passive', 'Passive'],
        ['rental_active_reps', 'REPS'],
        ['rental_active_str', 'STR'],
    ])('labels an expanded %s property as %s', (treatment, label) => {
        const detail = {
            income_source_id: 'is-1', property_name: 'Rental', tax_treatment: treatment, gross_rent: 1000,
            operating_expenses: 0, mortgage_interest: 0, property_tax: 0, depreciation: 0,
            loss_applied_to_income: 700, suspended_loss_carryforward: 0, net_income: 1000,
        } as unknown as NonNullable<ProjectionYear['rental_property_details']>[number];
        const data = [makeYear({ year: 2040, age: 65, tax_liability: 5000, rental_property_details: [detail] })];

        render(<IncomeTaxTab yearlyData={data} {...commonProps} expandedTaxYears={new Set([2040])} />);

        expect(screen.getByText(label)).toBeInTheDocument();
        expect(screen.getByText('$700')).toBeInTheDocument();
    });
});
