import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
    formatDate: (v: string | null | undefined) => v ?? '--',
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
    tableStyle: {},
    thStyle: {},
    tdStyle: {},
    trHoverStyle: {},
}));

vi.mock('recharts');

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

    describe('Zillow candidate dialog', () => {
        function renderDialog(onDismiss = vi.fn()) {
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
                    onDismissCandidates={onDismiss}
                />
            );
            return onDismiss;
        }

        it('is an accessible modal dialog with a name', () => {
            renderDialog();

            const dialog = screen.getByRole('dialog', { name: 'Multiple Properties Found' });
            expect(dialog).toHaveAttribute('aria-modal', 'true');
        });

        it('moves focus into the dialog', () => {
            renderDialog();

            expect(screen.getByRole('dialog')).toContainElement(document.activeElement as HTMLElement);
        });

        it('closes on Escape', () => {
            const onDismiss = renderDialog();

            fireEvent.keyDown(document, { key: 'Escape' });

            expect(onDismiss).toHaveBeenCalled();
        });
    });

    it('shows valuation dates as ISO dates', () => {
        render(
            <PropertyValuationSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                valuations={[valuation] as any}
                canWrite={false}
                refreshing={false}
                zillowCandidates={null}
                onRefreshValuation={vi.fn()}
                onSelectZpid={vi.fn()}
                onDismissCandidates={vi.fn()}
            />
        );

        expect(screen.getByRole('cell', { name: '2026-04-10' })).toBeInTheDocument();
    });
});
