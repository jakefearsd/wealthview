import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/properties', () => ({
    getDepreciationSchedule: vi.fn(),
}));

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
});
