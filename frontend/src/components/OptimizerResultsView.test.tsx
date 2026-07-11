import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../pages/SpendingOptimizerPage', () => ({
    computePlanDiagnostics: vi.fn(() => ({
        warnings: [],
        failureRateSeverity: 'good',
        phases: [],
    })),
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('./SpendingCorridorChart', () => ({ default: () => <div data-testid="corridor-chart" /> }));
vi.mock('./PortfolioFanChart', () => ({ default: () => <div data-testid="fan-chart" /> }));
vi.mock('./TaxSavingsSummary', () => ({ default: () => <div data-testid="tax-summary" /> }));
vi.mock('./ConversionScheduleTable', () => ({ default: () => <div data-testid="conv-schedule" /> }));
vi.mock('./TraditionalBalanceChart', () => ({ default: () => <div data-testid="trad-balance" /> }));
vi.mock('./NearTermSpendingGuide', () => ({ default: () => <div data-testid="near-term" /> }));

import { computePlanDiagnostics } from '../pages/SpendingOptimizerPage';
import OptimizerResultsView from './OptimizerResultsView';

const baseResult = {
    id: 'g-1',
    name: 'Opt 1',
    stale: false,
    failure_rate: 0.05,
    success_probability: 0.91,
    percentile10_final: 500000,
    phases: [],
    yearly_spending: [
        { year: 2035, sustainable_spending: 80000, portfolio_balance_p25: 1_000_000 },
    ],
};

describe('OptimizerResultsView', () => {
    it('renders failure rate card with formatted value', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={baseResult as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('failure-rate-card')).toBeInTheDocument();
        expect(screen.getByText('5.0%')).toBeInTheDocument();
    });

    it('renders success probability card with formatted value', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={baseResult as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('success-probability-card')).toBeInTheDocument();
        expect(screen.getByText('91%')).toBeInTheDocument();
    });

    it('shows stale banner with re-optimize button when profile is stale', () => {
        const onReoptimize = vi.fn();
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, stale: true } as any}
                onReoptimize={onReoptimize}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByText(/profile is stale/i)).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /Re-optimize/i }));
        expect(onReoptimize).toHaveBeenCalled();
    });

    it('renders warning banner when diagnostics produce warnings', () => {
        vi.mocked(computePlanDiagnostics).mockReturnValueOnce({
            warnings: ['High failure rate — consider reducing spending'],
            failureRateSeverity: 'danger',
            phases: [],
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        } as any);
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={baseResult as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('warning-banner')).toBeInTheDocument();
        expect(screen.getByText(/High failure rate/)).toBeInTheDocument();
    });

    it('embeds the expected child charts', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={baseResult as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('corridor-chart')).toBeInTheDocument();
        expect(screen.getByTestId('fan-chart')).toBeInTheDocument();
    });
});
