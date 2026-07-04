import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import TaxShieldTab from './TaxShieldTab';
import type { TaxShieldSummary } from '../utils/projectionCalcs';

function makeSummary(overrides: Partial<TaxShieldSummary> = {}): TaxShieldSummary {
    return {
        totalDepreciation: 120000,
        totalLossApplied: 45000,
        estimatedTaxSavings: 9900,
        rothConversionSheltered: 20000,
        suspendedLossRemaining: 5000,
        perProperty: [],
        ...overrides,
    };
}

describe('TaxShieldTab', () => {
    it('renders the headline totals', () => {
        render(<TaxShieldTab summary={makeSummary()} />);

        expect(screen.getByText('Depreciation Tax Shield Summary')).toBeInTheDocument();
        expect(screen.getByText('$120,000.00')).toBeInTheDocument();
        expect(screen.getByText('$45,000.00')).toBeInTheDocument();
        expect(screen.getByText('$9,900.00')).toBeInTheDocument();
        expect(screen.getByText('$20,000.00')).toBeInTheDocument();
        expect(screen.getByText('$5,000.00')).toBeInTheDocument();
    });

    it('omits the per-property table when there are no properties', () => {
        render(<TaxShieldTab summary={makeSummary()} />);

        expect(screen.queryByText('Per-Property Breakdown')).not.toBeInTheDocument();
    });

    it('renders one row per property with its classification badge', () => {
        render(
            <TaxShieldTab
                summary={makeSummary({
                    perProperty: [
                        { name: 'Main St Duplex', taxTreatment: 'rental_passive', depreciation: 80000, lossApplied: 30000 },
                        { name: 'Beach Cottage', taxTreatment: 'rental_str', depreciation: 40000, lossApplied: 15000 },
                    ],
                })}
            />
        );

        expect(screen.getByText('Per-Property Breakdown')).toBeInTheDocument();
        expect(screen.getByText('Main St Duplex')).toBeInTheDocument();
        expect(screen.getByText('Passive')).toBeInTheDocument();
        expect(screen.getByText('Beach Cottage')).toBeInTheDocument();
        expect(screen.getByText('STR')).toBeInTheDocument();
    });
});
