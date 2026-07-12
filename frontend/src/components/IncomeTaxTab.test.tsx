import { render, screen } from '@testing-library/react';
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
});
