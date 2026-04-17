import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    tableStyle: {},
}));

import DataTableTab from './DataTableTab';

const baseYear = {
    year: 2035,
    age: 65,
    start_balance: 1_000_000,
    contributions: 0,
    growth: 40000,
    withdrawals: 45000,
    end_balance: 995000,
    income_streams_total: 10000,
    retired: true,
};

describe('DataTableTab', () => {
    const commonProps = {
        hasPoolData: false,
        hasSpendingData: true,
        hasSurplusReinvested: false,
        computeTotalSpending: () => 45000,
        onDownloadCsv: vi.fn(),
    };

    it('renders a row per year', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<DataTableTab yearlyData={[baseYear] as any} {...commonProps} />);
        expect(screen.getByText('2035')).toBeInTheDocument();
        expect(screen.getByText('65')).toBeInTheDocument();
    });

    it('renders a Total Spending column header', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<DataTableTab yearlyData={[baseYear] as any} {...commonProps} />);
        expect(screen.getByText('Total Spending')).toBeInTheDocument();
    });

    it('shows pool details toggle when hasPoolData is true', () => {
        render(
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            <DataTableTab yearlyData={[baseYear] as any} {...commonProps} hasPoolData={true} />
        );
        expect(screen.getByText('Show Pool Details')).toBeInTheDocument();
        fireEvent.click(screen.getByText('Show Pool Details'));
        expect(screen.getByText('Hide Pool Details')).toBeInTheDocument();
    });

    it('invokes onDownloadCsv', () => {
        const onDownloadCsv = vi.fn();
        render(
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            <DataTableTab yearlyData={[baseYear] as any} {...commonProps} onDownloadCsv={onDownloadCsv} />
        );
        fireEvent.click(screen.getByText('Download CSV'));
        expect(onDownloadCsv).toHaveBeenCalled();
    });
});
