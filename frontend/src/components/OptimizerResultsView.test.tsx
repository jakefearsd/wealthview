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

    it('renders the with-rules card when success_probability_with_rules is present', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, success_probability_with_rules: 0.97, gated_on: 'with_rules' } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('success-probability-with-rules-card')).toBeInTheDocument();
        expect(screen.getByText('With Guardrail Cuts')).toBeInTheDocument();
        expect(screen.getByText('97%')).toBeInTheDocument();
    });

    it('marks the "With Guardrail Cuts" card as certified when gated_on is with_rules', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, success_probability_with_rules: 0.97, gated_on: 'with_rules' } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('certified-badge-with-rules')).toBeInTheDocument();
        expect(screen.queryByTestId('certified-badge-success-probability')).not.toBeInTheDocument();
        expect(screen.getByText('With Guardrail Cuts')).toBeInTheDocument();
        expect(screen.getByText('If Never Adjusted')).toBeInTheDocument();
    });

    it('marks the "Success Probability" card as certified when gated_on is no_adaptation', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, success_probability_with_rules: 0.97, gated_on: 'no_adaptation' } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('certified-badge-success-probability')).toBeInTheDocument();
        expect(screen.queryByTestId('certified-badge-with-rules')).not.toBeInTheDocument();
        expect(screen.getByText('Success Probability')).toBeInTheDocument();
        expect(screen.getByText('If Rules Followed')).toBeInTheDocument();
    });

    it('omits the with-rules card when success_probability_with_rules is null', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, success_probability_with_rules: null } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.queryByTestId('success-probability-with-rules-card')).not.toBeInTheDocument();
    });

    it('renders the floor-reduced banner with the original floor success probability', () => {
        render(
            <OptimizerResultsView
                result={{
                    ...baseResult,
                    floor_reduced: true,
                    original_floor_success_probability: 0.62,
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('floor-reduced-banner')).toBeInTheDocument();
        expect(screen.getByText(/success is 62%/)).toBeInTheDocument();
    });

    it('omits the floor-reduced banner when floor_reduced is false', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, floor_reduced: false } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.queryByTestId('floor-reduced-banner')).not.toBeInTheDocument();
    });

    it('renders the fixed-return-share note when the share exceeds 50%', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, fixed_return_share: 0.75 } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('fixed-return-share-note')).toBeInTheDocument();
        expect(screen.getByText(/75% of this portfolio uses a fixed expected return/)).toBeInTheDocument();
    });

    it('omits the fixed-return-share note when the share is at or below 50%', () => {
        render(
            <OptimizerResultsView
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                result={{ ...baseResult, fixed_return_share: 0.5 } as any}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.queryByTestId('fixed-return-share-note')).not.toBeInTheDocument();
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
