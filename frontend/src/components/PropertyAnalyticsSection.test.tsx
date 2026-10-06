import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/properties', () => ({
    getDepreciationSchedule: vi.fn(),
}));

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

const { yAxisProps } = vi.hoisted(() => ({ yAxisProps: { tickFormatter: undefined as undefined | ((v: number) => string) } }));
// Local factory instead of the shared mock: this file needs to see the YAxis tickFormatter prop.
vi.mock('recharts', () => {
    const Passthrough = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>;
    const Nothing = () => null;
    return {
        ResponsiveContainer: Passthrough,
        LineChart: Passthrough,
        Line: Nothing,
        XAxis: Nothing,
        Tooltip: Nothing,
        Legend: Nothing,
        YAxis: (props: { tickFormatter?: (v: number) => string }) => {
            yAxisProps.tickFormatter = props.tickFormatter;
            return null;
        },
    };
});

vi.mock('./HelpText', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));
vi.mock('./InfoSection', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

import { getDepreciationSchedule } from '../api/properties';
import PropertyAnalyticsSection from './PropertyAnalyticsSection';

const analytics = {
    property_type: 'investment',
    total_appreciation: 100000,
    appreciation_percent: 0.25,
    mortgage_progress: null,
    equity_growth: [],
    cap_rate: 0.06,
    annual_noi: 24000,
    cash_on_cash_return: 0.08,
    annual_net_cash_flow: 12000,
    total_cash_invested: 100000,
};

describe('PropertyAnalyticsSection', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getDepreciationSchedule).mockResolvedValue(null as any);
    });

    it('renders appreciation and NOI metrics', () => {
        render(
            <PropertyAnalyticsSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                analytics={analytics as any}
                analyticsYear={2026}
                analyticsYearOptions={[2024, 2025, 2026]}
                onYearChange={vi.fn()}
                propertyId="p-1"
                depreciationMethod="none"
            />
        );
        expect(screen.getAllByText('$100,000').length).toBeGreaterThan(0);
        // appreciation_percent rendered with .toFixed(2): 0.25 -> "0.25%"
        expect(screen.getByText('0.25%')).toBeInTheDocument();
    });

    it('does not fetch depreciation schedule when method is "none"', () => {
        render(
            <PropertyAnalyticsSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                analytics={analytics as any}
                analyticsYear={2026}
                analyticsYearOptions={[2024, 2025, 2026]}
                onYearChange={vi.fn()}
                propertyId="p-1"
                depreciationMethod="none"
            />
        );
        expect(getDepreciationSchedule).not.toHaveBeenCalled();
    });

    it('fetches depreciation schedule when method is not "none"', () => {
        render(
            <PropertyAnalyticsSection
                // eslint-disable-next-line @typescript-eslint/no-explicit-any
                analytics={analytics as any}
                analyticsYear={2026}
                analyticsYearOptions={[2024, 2025, 2026]}
                onYearChange={vi.fn()}
                propertyId="p-1"
                depreciationMethod="straight_line"
            />
        );
        expect(getDepreciationSchedule).toHaveBeenCalledWith('p-1');
    });

    it('refetches the depreciation schedule when its inputs key changes', () => {
        const props = {
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            analytics: analytics as any,
            analyticsYear: 2026,
            analyticsYearOptions: [2026],
            onYearChange: vi.fn(),
            propertyId: 'p-1',
            depreciationMethod: 'straight_line',
        };
        const { rerender } = render(<PropertyAnalyticsSection {...props} depreciationInputsKey="land-40000" />);
        expect(getDepreciationSchedule).toHaveBeenCalledTimes(1);

        rerender(<PropertyAnalyticsSection {...props} depreciationInputsKey="land-60000" />);

        expect(getDepreciationSchedule).toHaveBeenCalledTimes(2);
    });

    const baseProps = {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        analytics: analytics as any,
        analyticsYear: 2026,
        analyticsYearOptions: [2026],
        onYearChange: vi.fn(),
        propertyId: 'p-1',
    };

    it('shows the full-year depreciation in the header, not the partial first year', async () => {
        vi.mocked(getDepreciationSchedule).mockResolvedValue({
            depreciation_method: 'straight_line',
            depreciable_basis: 360000,
            useful_life_years: 27.5,
            in_service_date: '2018-07-01',
            bonus_depreciation_rate: null,
            cost_seg_allocations: null,
            class_breakdowns: null,
            schedule: [
                { tax_year: 2018, annual_depreciation: 6000, cumulative_taken: 6000, remaining_basis: 354000 },
                { tax_year: 2019, annual_depreciation: 13090.91, cumulative_taken: 19090.91, remaining_basis: 340909.09 },
                { tax_year: 2020, annual_depreciation: 13090.91, cumulative_taken: 32181.82, remaining_basis: 327818.18 },
            ],
        } as never);
        render(<PropertyAnalyticsSection {...baseProps} depreciationMethod="straight_line" />);

        expect(await screen.findByText('Annual: $13,090.91')).toBeInTheDocument();
    });

    it('formats the equity growth Y axis as compact dollars', () => {
        const withGrowth = {
            ...analytics,
            equity_growth: [{ month: '2026-01', equity: 600000, property_value: 900000, mortgage_balance: 300000 }],
        };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PropertyAnalyticsSection {...baseProps} analytics={withGrowth as any} depreciationMethod="none" />);

        expect(yAxisProps.tickFormatter?.(600000)).toBe('$600k');
    });
});
