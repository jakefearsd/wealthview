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
import { makeProfile } from '../testutil/builders';

const baseResult = makeProfile({
    id: 'g-1',
    name: 'Opt 1',
    stale: false,
    failure_rate: 0.05,
    success_probability: 0.91,
    percentile10_final: 500000,
    phases: [],
    yearly_spending: [
        {
            year: 2035, age: 67, recommended: 80000, corridor_low: 70000, corridor_high: 90000,
            essential_floor: 40000, discretionary: 40000, income_offset: 0, portfolio_withdrawal: 80000,
            phase_name: 'Go-go', portfolio_balance_median: 1_200_000, portfolio_balance_p10: 700_000,
            portfolio_balance_p25: 1_000_000,
        },
    ],
});

describe('OptimizerResultsView', () => {
    it('renders failure rate card with formatted value', () => {
        render(
            <OptimizerResultsView
                result={baseResult}
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
                result={baseResult}
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
                result={{ ...baseResult, stale: true }}
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
            overallAchievement: 0,
            depletionAgeP10: null,
            depletionAgeP25: null,
        });
        render(
            <OptimizerResultsView
                result={baseResult}
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
                result={{ ...baseResult, success_probability_with_rules: 0.97, gated_on: 'with_rules' }}
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
                result={{ ...baseResult, success_probability_with_rules: 0.97, gated_on: 'with_rules' }}
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
                result={{ ...baseResult, success_probability_with_rules: 0.97, gated_on: 'no_adaptation' }}
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
                result={{ ...baseResult, success_probability_with_rules: null }}
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
                }}
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
                result={{ ...baseResult, floor_reduced: false }}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.queryByTestId('floor-reduced-banner')).not.toBeInTheDocument();
    });

    it('renders the fixed-return-share note when the share exceeds 50%', () => {
        render(
            <OptimizerResultsView
                result={{ ...baseResult, fixed_return_share: 0.75 }}
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
                result={{ ...baseResult, fixed_return_share: 0.5 }}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.queryByTestId('fixed-return-share-note')).not.toBeInTheDocument();
    });

    describe('stochastic mortality (sub-project B)', () => {
        const stochasticMortality = {
            lifetime_success_probability: 0.94,
            longevity_conditional: { age: 95, probability: 0.81, trial_fraction: 0.32 },
            first_death_age: { p10: 78, median: 84, p90: 91 },
            second_death_age: { p10: 85, median: 90, p90: 96 },
        };

        it('renders the lifetime and longevity-conditional success labels with the median second-death age', () => {
            render(
                <OptimizerResultsView
                    result={{ ...baseResult, stochastic_mortality: stochasticMortality }}
                    onReoptimize={vi.fn()}
                    retirementDate="2035-01-01"
                />
            );
            expect(screen.getByTestId('stochastic-mortality-card')).toBeInTheDocument();
            expect(screen.getByText(/Lifetime Success/i)).toBeInTheDocument();
            expect(screen.getByText(/Longevity-Conditional Success/i)).toBeInTheDocument();
            expect(screen.getByText(/survivor lives to age 95/i)).toBeInTheDocument();
            expect(screen.getByText('94%')).toBeInTheDocument();
            expect(screen.getByText('81%')).toBeInTheDocument();
            expect(screen.getByText(/Median 90/)).toBeInTheDocument();
            // The existing fixed-death success number stays visible for comparison.
            expect(screen.getByTestId('success-probability-card')).toBeInTheDocument();
            expect(screen.getByText('91%')).toBeInTheDocument();
        });

        it('renders neither stochastic-mortality label and does not crash when the block is null', () => {
            render(
                <OptimizerResultsView
                    result={{ ...baseResult, stochastic_mortality: null }}
                    onReoptimize={vi.fn()}
                    retirementDate="2035-01-01"
                />
            );
            expect(screen.queryByTestId('stochastic-mortality-card')).not.toBeInTheDocument();
            expect(screen.queryByText(/Lifetime Success/i)).not.toBeInTheDocument();
            expect(screen.queryByText(/Longevity-Conditional Success/i)).not.toBeInTheDocument();
        });

        it('renders neither stochastic-mortality label and does not crash when the field is absent entirely', () => {
            render(
                <OptimizerResultsView
                    result={baseResult}
                    onReoptimize={vi.fn()}
                    retirementDate="2035-01-01"
                />
            );
            expect(screen.queryByTestId('stochastic-mortality-card')).not.toBeInTheDocument();
        });
    });

    it('embeds the expected child charts', () => {
        render(
            <OptimizerResultsView
                result={baseResult}
                onReoptimize={vi.fn()}
                retirementDate="2035-01-01"
            />
        );
        expect(screen.getByTestId('corridor-chart')).toBeInTheDocument();
        expect(screen.getByTestId('fan-chart')).toBeInTheDocument();
    });
});
