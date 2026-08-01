import { render, screen, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import type { ConversionYearDetail } from '../types/projection';

vi.mock('recharts');

import TraditionalBalanceChart from './TraditionalBalanceChart';

function detail(age: number, traditional: number, roth: number): ConversionYearDetail {
    return {
        age,
        traditional_balance_after: traditional,
        roth_balance_after: roth,
    } as ConversionYearDetail;
}

function chartRows(): Array<Record<string, unknown>> {
    return JSON.parse(screen.getByTestId('composed-chart').getAttribute('data-chart-data') ?? '[]');
}

/**
 * TraditionalBalanceChart had zero coverage: its only consumer, OptimizerResultsView, mocks it out.
 * It plots the Roth-conversion trajectory — the traditional balance draining as the Roth fills —
 * and marks the age the traditional pool is exhausted, which is the headline number of the
 * conversion optimiser.
 */
describe('TraditionalBalanceChart', () => {
    const years = [
        detail(65, 800_000, 100_000),
        detail(66, 700_000, 220_000),
        detail(67, 580_000, 350_000),
    ];

    it('reshapes each year into the two plotted balances keyed by age', () => {
        render(<TraditionalBalanceChart years={years} exhaustionAge={72} />);

        expect(chartRows()).toEqual([
            { age: 65, traditional: 800_000, roth: 100_000 },
            { age: 66, traditional: 700_000, roth: 220_000 },
            { age: 67, traditional: 580_000, roth: 350_000 },
        ]);
    });

    it('plots a traditional and a roth line', () => {
        render(<TraditionalBalanceChart years={years} exhaustionAge={72} />);

        const keys = screen.getAllByTestId('line').map((el) => el.getAttribute('data-key'));
        expect(keys).toEqual(['traditional', 'roth']);
    });

    it('labels each line for the legend', () => {
        render(<TraditionalBalanceChart years={years} exhaustionAge={72} />);

        expect(screen.getByText('Traditional IRA')).toBeInTheDocument();
        expect(screen.getByText('Roth IRA')).toBeInTheDocument();
    });

    it('marks the exhaustion age with its value in the label', () => {
        render(<TraditionalBalanceChart years={years} exhaustionAge={72} />);

        expect(screen.getByTestId('reference-line'))
            .toHaveAttribute('data-label', 'Exhaustion (72)');
    });

    it('shows a placeholder instead of an empty chart when there is no trajectory', () => {
        render(<TraditionalBalanceChart years={[]} exhaustionAge={72} />);

        expect(screen.getByText('No balance trajectory data available.')).toBeInTheDocument();
        expect(screen.queryByTestId('composed-chart')).not.toBeInTheDocument();
    });

    it('labels the tooltip by age rather than by row index', () => {
        render(<TraditionalBalanceChart years={years} exhaustionAge={72} />);

        expect(within(screen.getByTestId('tooltip')).getByTestId('tooltip-label'))
            .toHaveTextContent('Age 65');
    });
});
