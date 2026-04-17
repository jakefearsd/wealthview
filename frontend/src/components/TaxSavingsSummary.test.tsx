import { render, screen } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

vi.mock('../utils/styles', () => ({
    cardStyle: {},
}));

import TaxSavingsSummary from './TaxSavingsSummary';

const baseSchedule = {
    years: [],
    lifetime_tax_with_conversions: 120000,
    lifetime_tax_without: 180000,
    tax_savings: 60000,
    conversion_bracket_rate: 0.22,
    rmd_target_bracket_rate: 0.12,
    target_traditional_balance: 800000,
    rmd_bracket_headroom: 0.10,
    exhaustion_age: 75,
    exhaustion_target_met: true,
    mc_exhaustion_pct: 0.02,
};

describe('TaxSavingsSummary', () => {
    it('renders lifetime tax values and savings', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<TaxSavingsSummary schedule={baseSchedule as any} />);
        expect(screen.getByText('$120,000')).toBeInTheDocument();
        expect(screen.getByText('$180,000')).toBeInTheDocument();
        expect(screen.getByText('$60,000')).toBeInTheDocument();
    });

    it('renders the warning when exhaustion target not met', () => {
        const schedule = { ...baseSchedule, exhaustion_target_met: false };
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<TaxSavingsSummary schedule={schedule as any} />);
        expect(screen.getByText(/Traditional IRA exhaustion target/i)).toBeInTheDocument();
    });

    it('formats bracket rates as percentages', () => {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        render(<TaxSavingsSummary schedule={baseSchedule as any} />);
        expect(screen.getByText('22%')).toBeInTheDocument();
        expect(screen.getByText('12%')).toBeInTheDocument();
    });
});
