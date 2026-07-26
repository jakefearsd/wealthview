import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ProjectionYear, ScenarioIncomeSourceResponse } from '../types/projection';

vi.mock('recharts');

import IncomeStreamsChart from './IncomeStreamsChart';

/** Reads the shaped rows the component handed to BarChart via the shared recharts mock. */
function barChartData(): Record<string, number | string>[] | null {
    const raw = screen.queryByTestId('bar-chart')?.getAttribute('data-chart-data');
    return raw ? JSON.parse(raw) : null;
}

function makeYear(overrides: Partial<ProjectionYear> & { year: number }): ProjectionYear {
    return {
        age: 65 + overrides.year - 2045,
        start_balance: 0,
        contributions: 0,
        growth: 0,
        withdrawals: 0,
        end_balance: 0,
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
        self_employment_tax: null,
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
        rental_property_details: null,
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

const mockIncomeSources: ScenarioIncomeSourceResponse[] = [
    {
        income_source_id: 'src-pension-001',
        name: 'Pension',
        income_type: 'pension',
        annual_amount: 24000,
        override_annual_amount: null,
        effective_amount: 24000,
        start_age: 65,
        end_age: null,
        inflation_rate: 0.02,
        one_time: false,
    },
];

describe('IncomeStreamsChart', () => {
    it('passes non-zero income data to chart when income_by_source is populated', () => {
        const data: ProjectionYear[] = [
            makeYear({ year: 2044, retired: false }),
            makeYear({
                year: 2045, retired: true,
                income_by_source: { 'src-pension-001': 24000 },
            }),
            makeYear({
                year: 2046, retired: true,
                income_by_source: { 'src-pension-001': 24480 },
            }),
        ];

        render(
            <IncomeStreamsChart
                data={data}
                incomeSources={mockIncomeSources}
                retirementYear={2045}
            />
        );

        expect(barChartData()).not.toBeNull();
        expect(barChartData()).toHaveLength(2); // only retired years
        expect(barChartData()![0]['src-pension-001']).toBe(24000);
        expect(barChartData()![1]['src-pension-001']).toBe(24480);
    });

    it('returns null when no retired years exist', () => {
        const data: ProjectionYear[] = [
            makeYear({ year: 2044, retired: false }),
        ];

        const { container } = render(
            <IncomeStreamsChart
                data={data}
                incomeSources={mockIncomeSources}
                retirementYear={2045}
            />
        );

        expect(container.innerHTML).toBe('');
    });

    it('returns null when no income sources provided', () => {
        const data: ProjectionYear[] = [
            makeYear({ year: 2045, retired: true, income_by_source: { 'src-pension-001': 24000 } }),
        ];

        const { container } = render(
            <IncomeStreamsChart
                data={data}
                incomeSources={[]}
                retirementYear={2045}
            />
        );

        expect(container.innerHTML).toBe('');
    });

    it('defaults to zero when income_by_source is null for a retired year', () => {
        const data: ProjectionYear[] = [
            makeYear({ year: 2045, retired: true, income_by_source: null }),
        ];

        render(
            <IncomeStreamsChart
                data={data}
                incomeSources={mockIncomeSources}
                retirementYear={2045}
            />
        );

        expect(barChartData()).not.toBeNull();
        expect(barChartData()![0]['src-pension-001']).toBe(0);
    });
});
