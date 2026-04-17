import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('./BalanceChart', () => ({
    default: () => <div data-testid="balance-chart" />,
}));
vi.mock('./FlowsChart', () => ({
    default: () => <div data-testid="flows-chart" />,
}));
vi.mock('./SpendingChart', () => ({
    default: () => <div data-testid="spending-chart" />,
}));

import ProjectionChart from './ProjectionChart';

describe('ProjectionChart', () => {
    it('renders the balance chart when mode is balance', () => {
        render(<ProjectionChart data={[]} retirementYear={2035} mode="balance" />);
        expect(screen.getByTestId('balance-chart')).toBeInTheDocument();
    });

    it('renders the flows chart when mode is flows', () => {
        render(<ProjectionChart data={[]} retirementYear={2035} mode="flows" />);
        expect(screen.getByTestId('flows-chart')).toBeInTheDocument();
    });

    it('renders the spending chart when mode is spending', () => {
        render(<ProjectionChart data={[]} retirementYear={2035} mode="spending" />);
        expect(screen.getByTestId('spending-chart')).toBeInTheDocument();
    });
});
