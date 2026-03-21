import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import PortfolioFanChart from './PortfolioFanChart';
import type { GuardrailYearlySpending } from '../types/projection';

// Mock recharts to avoid rendering issues in test environment
vi.mock('recharts', () => ({
    ComposedChart: ({ children }: { children: React.ReactNode }) => <div data-testid="composed-chart">{children}</div>,
    Area: ({ name }: { name: string }) => <div data-testid={`area-${name}`} />,
    Line: ({ name }: { name: string }) => <div data-testid={`line-${name}`} />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    CartesianGrid: () => <div />,
    Tooltip: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
    Legend: () => <div />,
}));

function makeYearlySpending(overrides: Partial<GuardrailYearlySpending> = {}): GuardrailYearlySpending {
    return {
        year: 2030, age: 62, recommended: 75000, corridor_low: 62000, corridor_high: 91000,
        essential_floor: 30000, discretionary: 45000, income_offset: 0, portfolio_withdrawal: 75000,
        phase_name: 'Early', portfolio_balance_median: 480000, portfolio_balance_p10: 200000,
        portfolio_balance_p25: 350000, portfolio_balance_p75: 650000,
        ...overrides,
    };
}

describe('PortfolioFanChart', () => {
    it('renders the chart container', () => {
        render(<PortfolioFanChart yearlySpending={[makeYearlySpending()]} />);
        expect(screen.getByTestId('portfolio-fan-chart')).toBeInTheDocument();
    });

    it('renders chart with data series', () => {
        render(<PortfolioFanChart yearlySpending={[makeYearlySpending()]} />);
        expect(screen.getByTestId('composed-chart')).toBeInTheDocument();
        expect(screen.getByTestId('area-10th-75th Percentile')).toBeInTheDocument();
        expect(screen.getByTestId('area-25th-50th Percentile')).toBeInTheDocument();
        expect(screen.getByTestId('line-Median (p50)')).toBeInTheDocument();
        expect(screen.getByTestId('line-10th Percentile')).toBeInTheDocument();
    });

    it('renders empty message when no data', () => {
        render(<PortfolioFanChart yearlySpending={[]} />);
        expect(screen.getByText('No portfolio balance data available.')).toBeInTheDocument();
    });

    it('handles multiple years of data', () => {
        const data = [
            makeYearlySpending({ age: 62 }),
            makeYearlySpending({ age: 63, portfolio_balance_median: 460000 }),
            makeYearlySpending({ age: 64, portfolio_balance_median: 440000 }),
        ];
        render(<PortfolioFanChart yearlySpending={data} />);
        expect(screen.getByTestId('portfolio-fan-chart')).toBeInTheDocument();
    });
});
