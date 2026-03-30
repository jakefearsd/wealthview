import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { GuardrailYearlySpending, GuardrailPhase } from '../types/projection';

vi.mock('recharts', () => ({
    ComposedChart: ({ data, children }: { data: unknown[]; children: React.ReactNode }) => (
        <div data-testid="composed-chart" data-chart-data={JSON.stringify(data)}>{children}</div>
    ),
    Area: ({ dataKey, name }: { dataKey: string; name: string }) => (
        <div data-testid={`area-${dataKey}`} data-name={name} />
    ),
    Line: ({ dataKey, name }: { dataKey: string; name: string }) => (
        <div data-testid={`line-${dataKey}`} data-name={name} />
    ),
    XAxis: () => <div />,
    YAxis: () => <div />,
    CartesianGrid: () => <div />,
    Tooltip: () => <div />,
    Legend: () => <div />,
    ReferenceLine: ({ label }: { label: { value: string } }) => (
        <div data-testid="reference-line" data-label={label?.value} />
    ),
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import SpendingCorridorChart from './SpendingCorridorChart';

const sampleSpending: GuardrailYearlySpending[] = [
    { year: 2030, age: 62, recommended: 75000, corridor_low: 62000, corridor_high: 91000, essential_floor: 30000, discretionary: 45000, income_offset: 12000, portfolio_withdrawal: 63000, phase_name: 'Early', portfolio_balance_median: 480000, portfolio_balance_p10: 200000, portfolio_balance_p25: 350000, portfolio_balance_p55: 650000 },
    { year: 2031, age: 63, recommended: 76000, corridor_low: 63000, corridor_high: 92000, essential_floor: 30000, discretionary: 46000, income_offset: 12500, portfolio_withdrawal: 63500, phase_name: 'Early', portfolio_balance_median: 440000, portfolio_balance_p10: 180000, portfolio_balance_p25: 320000, portfolio_balance_p55: 600000 },
    { year: 2032, age: 64, recommended: 50000, corridor_low: 40000, corridor_high: 65000, essential_floor: 30000, discretionary: 20000, income_offset: 0, portfolio_withdrawal: 50000, phase_name: 'Mid', portfolio_balance_median: 350000, portfolio_balance_p10: 120000, portfolio_balance_p25: 240000, portfolio_balance_p55: 500000 },
];

const samplePhases: GuardrailPhase[] = [
    { name: 'Early', start_age: 62, end_age: 63, priority_weight: 3, target_spending: 80000 },
    { name: 'Mid', start_age: 64, end_age: null, priority_weight: 1, target_spending: 50000 },
];

describe('SpendingCorridorChart', () => {
    it('renders empty state when no data', () => {
        render(<SpendingCorridorChart yearlySpending={[]} phases={[]} />);
        expect(screen.getByText('No spending data available.')).toBeInTheDocument();
    });

    it('renders chart with data', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);
        expect(screen.getByTestId('composed-chart')).toBeInTheDocument();
    });

    it('transforms data correctly for chart consumption', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        const chartEl = screen.getByTestId('composed-chart');
        const chartData = JSON.parse(chartEl.getAttribute('data-chart-data')!);

        expect(chartData).toHaveLength(3);

        // Verify first data point transformation
        const first = chartData[0];
        expect(first.age).toBe(62);
        expect(first.recommended).toBe(75000);
        expect(first.corridorLow).toBe(62000);
        expect(first.corridorHigh).toBe(91000);
        expect(first.corridorRange).toEqual([62000, 91000]);
        expect(first.essentialFloor).toBe(30000);
        expect(first.incomeOffset).toBe(12000);
        expect(first.portfolioWithdrawal).toBe(63000);
        expect(first.phaseName).toBe('Early');
    });

    it('corridor range always has low <= high for all data points', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        const chartEl = screen.getByTestId('composed-chart');
        const chartData = JSON.parse(chartEl.getAttribute('data-chart-data')!);

        for (const point of chartData) {
            expect(point.corridorRange[0]).toBeLessThanOrEqual(point.corridorRange[1]);
            expect(point.corridorLow).toBeLessThanOrEqual(point.corridorHigh);
        }
    });

    it('recommended spending is within corridor bounds', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        const chartEl = screen.getByTestId('composed-chart');
        const chartData = JSON.parse(chartEl.getAttribute('data-chart-data')!);

        for (const point of chartData) {
            expect(point.recommended).toBeGreaterThanOrEqual(point.corridorLow);
            expect(point.recommended).toBeLessThanOrEqual(point.corridorHigh);
        }
    });

    it('essential floor is less than or equal to recommended', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        const chartEl = screen.getByTestId('composed-chart');
        const chartData = JSON.parse(chartEl.getAttribute('data-chart-data')!);

        for (const point of chartData) {
            expect(point.essentialFloor).toBeLessThanOrEqual(point.recommended);
        }
    });

    it('portfolio withdrawal + income offset >= recommended (spending identity)', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        const chartEl = screen.getByTestId('composed-chart');
        const chartData = JSON.parse(chartEl.getAttribute('data-chart-data')!);

        for (const point of chartData) {
            const totalFunding = point.portfolioWithdrawal + point.incomeOffset;
            expect(totalFunding).toBeGreaterThanOrEqual(point.recommended);
        }
    });

    it('renders phase reference lines', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        const refLines = screen.getAllByTestId('reference-line');
        expect(refLines).toHaveLength(2);
        expect(refLines[0].getAttribute('data-label')).toBe('Early');
        expect(refLines[1].getAttribute('data-label')).toBe('Mid');
    });

    it('renders all chart layers (corridor area, income area, recommended line, floor line)', () => {
        render(<SpendingCorridorChart yearlySpending={sampleSpending} phases={samplePhases} />);

        expect(screen.getByTestId('area-corridorRange')).toBeInTheDocument();
        expect(screen.getByTestId('area-incomeOffset')).toBeInTheDocument();
        expect(screen.getByTestId('line-recommended')).toBeInTheDocument();
        expect(screen.getByTestId('line-essentialFloor')).toBeInTheDocument();
    });
});
