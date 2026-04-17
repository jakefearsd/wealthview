import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('recharts', () => ({
    LineChart: ({ children }: { children: React.ReactNode }) => <div data-testid="line-chart">{children}</div>,
    Line: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    Legend: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import PropertyValuationSection from './PropertyValuationSection';

const valuation = {
    id: 'v-1',
    valuation_date: '2026-04-10',
    value: 500_000,
    source: 'zillow',
};

const zillowCandidate = {
    zpid: '12345',
    address: '123 Oak',
    zestimate: 510_000,
};

describe('PropertyValuationSection', () => {
    it('renders the chart when valuations exist', () => {
        render(
            <PropertyValuationSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                valuations={[valuation] as any}
                canWrite={true}
                refreshing={false}
                zillowCandidates={null}
                onRefreshValuation={vi.fn()}
                onSelectZpid={vi.fn()}
                onDismissCandidates={vi.fn()}
            />
        );
        expect(screen.getByTestId('line-chart')).toBeInTheDocument();
    });

    it('calls onRefreshValuation when the refresh button is clicked', () => {
        const onRefresh = vi.fn();
        render(
            <PropertyValuationSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                valuations={[valuation] as any}
                canWrite={true}
                refreshing={false}
                zillowCandidates={null}
                onRefreshValuation={onRefresh}
                onSelectZpid={vi.fn()}
                onDismissCandidates={vi.fn()}
            />
        );
        const button = screen.getByRole('button', { name: /Refresh Valuation/i });
        fireEvent.click(button);
        expect(onRefresh).toHaveBeenCalled();
    });

    it('lists Zillow candidates when provided', () => {
        render(
            <PropertyValuationSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                valuations={[] as any}
                canWrite={true}
                refreshing={false}
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                zillowCandidates={[zillowCandidate] as any}
                onRefreshValuation={vi.fn()}
                onSelectZpid={vi.fn()}
                onDismissCandidates={vi.fn()}
            />
        );
        expect(screen.getByText('123 Oak')).toBeInTheDocument();
    });
});
