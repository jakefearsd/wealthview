import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import PortfolioFanChart from './PortfolioFanChart';
import type { GuardrailYearlySpending } from '../types/projection';

// Mock recharts to avoid rendering issues in test environment
vi.mock('recharts');

/**
 * The shared recharts mock gives every Area/Line a generic `data-testid`
 * ("area"/"line") plus a `data-key` attribute (from the real `dataKey` prop)
 * rather than a per-series testid — select the one matching `dataKey`.
 */
function seriesByDataKey(testId: 'area' | 'line', dataKey: string): HTMLElement {
    const match = screen.getAllByTestId(testId).find((el) => el.getAttribute('data-key') === dataKey);
    if (!match) throw new Error(`No <${testId}> with data-key="${dataKey}" found`);
    return match;
}

function makeYearlySpending(overrides: Partial<GuardrailYearlySpending> = {}): GuardrailYearlySpending {
    return {
        year: 2030, age: 62, recommended: 75000, corridor_low: 62000, corridor_high: 91000,
        essential_floor: 30000, discretionary: 45000, income_offset: 0, portfolio_withdrawal: 75000,
        phase_name: 'Early', portfolio_balance_median: 480000, portfolio_balance_p10: 200000,
        portfolio_balance_p25: 350000,
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
        expect(seriesByDataKey('area', 'outerBand')).toBeInTheDocument(); // 10th-50th Percentile
        expect(seriesByDataKey('area', 'innerBand')).toBeInTheDocument(); // 25th-50th Percentile
        expect(seriesByDataKey('line', 'median')).toBeInTheDocument(); // Median (p50)
        expect(seriesByDataKey('line', 'p10')).toBeInTheDocument(); // 10th Percentile
    });

    it('renders empty message when no data', () => {
        render(<PortfolioFanChart yearlySpending={[]} />);
        expect(screen.getByText('No portfolio balance data available.')).toBeInTheDocument();
    });

    it('renders the per-year statistics caveat below the chart', () => {
        render(<PortfolioFanChart yearlySpending={[makeYearlySpending()]} />);
        expect(screen.getByText(/Percentile bands are per-year statistics, not a single portfolio's path\./)).toBeInTheDocument();
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
