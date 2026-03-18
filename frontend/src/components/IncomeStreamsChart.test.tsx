import { render } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ProjectionYear, ScenarioIncomeSourceResponse } from '../types/projection';

let capturedBarChartData: Record<string, number | string>[] | null = null;

vi.mock('recharts', () => ({
    ResponsiveContainer: ({ children }: any) => <div>{children}</div>,
    BarChart: ({ data, children }: any) => {
        capturedBarChartData = data;
        return <div data-testid="bar-chart">{children}</div>;
    },
    Bar: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    Legend: () => <div />,
    CartesianGrid: () => <div />,
    ReferenceLine: () => <div />,
}));

import IncomeStreamsChart from './IncomeStreamsChart';

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
        capturedBarChartData = null;

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

        expect(capturedBarChartData).not.toBeNull();
        expect(capturedBarChartData).toHaveLength(2); // only retired years
        expect(capturedBarChartData![0]['src-pension-001']).toBe(24000);
        expect(capturedBarChartData![1]['src-pension-001']).toBe(24480);
    });

    it('returns null when no retired years exist', () => {
        capturedBarChartData = null;

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
        capturedBarChartData = null;

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
        capturedBarChartData = null;

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

        expect(capturedBarChartData).not.toBeNull();
        expect(capturedBarChartData![0]['src-pension-001']).toBe(0);
    });
});
