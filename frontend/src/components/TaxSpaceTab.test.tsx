import { render, screen, within } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/format', () => ({
    formatCurrency: (v: number) => `$${v.toLocaleString()}`,
}));

vi.mock('../utils/styles', () => ({
    tableStyle: {},
}));

import TaxSpaceTab from './TaxSpaceTab';
import type { TaxSpaceYear } from '../types/projection';

function makeYear(overrides: Partial<TaxSpaceYear> = {}): TaxSpaceYear {
    return {
        year: 2031,
        age: 62,
        magi: 70000,
        marginal_ordinary_rate: 0.12,
        bracket_room: [{ rate: 0.12, gross_ceiling: 126700, room: 56700 }],
        ltcg_zero_room: 26700,
        ltcg_fifteen_room: 500000,
        provisional_income: null,
        ss_base_threshold: null,
        ss_upper_threshold: null,
        ss_inclusion_rate: null,
        niit_headroom: 180000,
        irmaa_premium_year: null,
        irmaa_tier: null,
        irmaa_room_to_next_tier: null,
        irmaa_next_tier_annual_cost: null,
        effective_marginal_ordinary: 0.12,
        effective_marginal_ltcg: 0,
        ...overrides,
    };
}

describe('TaxSpaceTab', () => {
    it('renders one row per year with bracket, room and capital-gains room', () => {
        render(<TaxSpaceTab taxSpace={[makeYear()]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getByText('62')).toBeInTheDocument();
        expect(within(row).getByText('$70,000')).toBeInTheDocument();
        expect(within(row).getAllByText('12.0%').length).toBeGreaterThanOrEqual(2);
        expect(within(row).getByText('$56,700')).toBeInTheDocument();
        expect(within(row).getByText('to top of 12.0%')).toBeInTheDocument();
        expect(within(row).getByText('$26,700')).toBeInTheDocument();
        expect(within(row).getByText('$180,000')).toBeInTheDocument();
    });

    it('names the bracket the room is measured to when income sits below the deduction (0% marginal, 10% room)', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({
            magi: 8000,
            marginal_ordinary_rate: 0,
            bracket_room: [{ rate: 0.10, gross_ceiling: 29200, room: 21200 }],
        })]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getAllByText('0.0%').length).toBeGreaterThanOrEqual(1);
        expect(within(row).getByText('$21,200')).toBeInTheDocument();
        expect(within(row).getByText('to top of 10.0%')).toBeInTheDocument();
    });

    it('omits the room suffix when there is no bracket room data', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({ bracket_room: [] })]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).queryByText(/to top of/)).not.toBeInTheDocument();
    });

    it('shows dashes when Social Security and IRMAA do not apply', () => {
        render(<TaxSpaceTab taxSpace={[makeYear()]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(2);
    });

    it('labels the Social Security inclusion zone and the IRMAA tier with its premium year and annual cost cliff', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({
            provisional_income: 52000, ss_base_threshold: 32000, ss_upper_threshold: 44000,
            ss_inclusion_rate: 0.85,
            irmaa_premium_year: 2033, irmaa_tier: 0, irmaa_room_to_next_tier: 36000,
            irmaa_next_tier_annual_cost: 2100,
        })]} />);

        const row = screen.getByRole('row', { name: /2031/ });
        expect(within(row).getByText('85¢ per $1')).toBeInTheDocument();
        expect(within(row).getByText('Tier 0 · $36,000 to next tier (+$2,100/yr on 2033 premiums)')).toBeInTheDocument();
    });

    it('still names the premium year when the next-tier cost is unknown', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({
            irmaa_premium_year: 2033, irmaa_tier: 1, irmaa_room_to_next_tier: 8000,
            irmaa_next_tier_annual_cost: null,
        })]} />);

        expect(screen.getByText('Tier 1 · $8,000 to next tier (2033 premiums)')).toBeInTheDocument();
    });

    it('marks the IRMAA top tier when there is no next tier', () => {
        render(<TaxSpaceTab taxSpace={[makeYear({
            irmaa_premium_year: 2033, irmaa_tier: 5, irmaa_room_to_next_tier: null,
        })]} />);

        expect(screen.getByText('Tier 5 (top) · 2033 premiums')).toBeInTheDocument();
    });

    it('renders the column explainers and the planning disclaimer', () => {
        render(<TaxSpaceTab taxSpace={[makeYear()]} />);

        expect(screen.getByText(/not tax advice/i)).toBeInTheDocument();
        expect(screen.getByText(/0% capital-gains room/i)).toBeInTheDocument();
    });

    it('renders an empty-state message for an empty list', () => {
        render(<TaxSpaceTab taxSpace={[]} />);

        expect(screen.getByText(/no retirement years/i)).toBeInTheDocument();
    });
});
