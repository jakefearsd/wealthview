import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('../api/properties', () => ({
    getRoiAnalysis: vi.fn(),
}));

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

import { getRoiAnalysis } from '../api/properties';
import PropertyRoiCard from './PropertyRoiCard';

const incomeSource = {
    id: 'inc-1',
    name: 'Rental',
    income_type: 'rental_property',
    annual_amount: 24000,
    start_age: 60,
    end_age: null,
    inflation_rate: 0.02,
    one_time: false,
    tax_treatment: 'rental_passive',
    property_id: 'p-1',
};

const analysis = {
    income_source_name: 'Rental',
    annual_rent: 24000,
    comparison_years: 10,
    hold: {
        ending_property_value: 650000,
        ending_mortgage_balance: 150000,
        cumulative_net_cash_flow: 120000,
        ending_net_worth: 620000,
    },
    sell: {
        gross_proceeds: 500000,
        selling_costs: 30000,
        depreciation_recapture_tax: 10000,
        capital_gains_tax: 15000,
        net_proceeds: 445000,
        ending_net_worth: 540000,
    },
    advantage: 'hold' as const,
    advantage_amount: 80000,
};

describe('PropertyRoiCard', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        vi.mocked(getRoiAnalysis).mockResolvedValue(analysis as any);
    });

    it('fetches analysis on mount', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PropertyRoiCard propertyId="p-1" incomeSource={incomeSource as any} />);
        await waitFor(() => {
            expect(getRoiAnalysis).toHaveBeenCalled();
        });
    });

    it('renders the hold vs sell summary after fetch', async () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<PropertyRoiCard propertyId="p-1" incomeSource={incomeSource as any} />);
        // "Holding is better by $80,000 over 10 years" is rendered as mixed text nodes;
        // match any node whose textContent contains the distinctive phrase.
        await waitFor(() => {
            const matches = screen.getAllByText((_, node) => {
                return !!node?.textContent?.includes('Holding') && !!node?.textContent?.includes('$80,000');
            });
            expect(matches.length).toBeGreaterThan(0);
        });
    });
});
