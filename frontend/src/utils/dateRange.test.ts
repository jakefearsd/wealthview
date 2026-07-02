import { describe, it, expect } from 'vitest';
import { trailingTwelveMonthRange } from './dateRange';

describe('trailingTwelveMonthRange', () => {
    it('returns a 12-month window ending at the current month', () => {
        expect(trailingTwelveMonthRange(new Date(2026, 6, 15)))
            .toEqual({ from: '2025-08', to: '2026-07' });
    });

    it('stays within the current year in December instead of producing month 13', () => {
        expect(trailingTwelveMonthRange(new Date(2026, 11, 31)))
            .toEqual({ from: '2026-01', to: '2026-12' });
    });

    it('rolls the window across the year boundary in January', () => {
        expect(trailingTwelveMonthRange(new Date(2026, 0, 1)))
            .toEqual({ from: '2025-02', to: '2026-01' });
    });

    it('zero-pads single-digit months', () => {
        expect(trailingTwelveMonthRange(new Date(2026, 2, 10)))
            .toEqual({ from: '2025-04', to: '2026-03' });
    });
});
