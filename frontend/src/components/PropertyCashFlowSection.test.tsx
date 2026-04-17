import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

vi.mock('recharts', () => ({
    BarChart: ({ children }: { children: React.ReactNode }) => <div data-testid="bar-chart">{children}</div>,
    Bar: () => <div />,
    XAxis: () => <div />,
    YAxis: () => <div />,
    Tooltip: () => <div />,
    Legend: () => <div />,
    ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('./PropertyTransactionForm', () => ({
    default: ({ title }: { title: string }) => <div data-testid="property-transaction-form">{title}</div>,
}));

import PropertyCashFlowSection from './PropertyCashFlowSection';

const month = {
    month: '2026-04',
    total_expenses: 1500,
    net_cash_flow: -1500,
    rent_estimate: 0,
};

describe('PropertyCashFlowSection', () => {
    it('renders the expense chart when cash flow has data', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PropertyCashFlowSection cashFlow={[month] as any} canWrite={true} onAddExpense={vi.fn()} />);
        expect(screen.getByTestId('bar-chart')).toBeInTheDocument();
    });

    it('skips the chart when cash flow is empty', () => {
        render(<PropertyCashFlowSection cashFlow={[]} canWrite={true} onAddExpense={vi.fn()} />);
        expect(screen.queryByTestId('bar-chart')).not.toBeInTheDocument();
    });

    it('renders the add-expense form when canWrite is true', () => {
        render(<PropertyCashFlowSection cashFlow={[]} canWrite={true} onAddExpense={vi.fn()} />);
        expect(screen.getByTestId('property-transaction-form')).toBeInTheDocument();
    });

    it('hides the add form when canWrite is false', () => {
        render(<PropertyCashFlowSection cashFlow={[]} canWrite={false} onAddExpense={vi.fn()} />);
        expect(screen.queryByTestId('property-transaction-form')).not.toBeInTheDocument();
    });
});
