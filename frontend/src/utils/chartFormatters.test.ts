import { describe, it, expect } from 'vitest';
import { formatDollarAxis, formatDollarTooltip, formatPercentAxis } from './chartFormatters';

describe('formatDollarAxis', () => {
    it('formats values >= $1M with M suffix and one decimal', () => {
        expect(formatDollarAxis(1_500_000)).toBe('$1.5M');
        expect(formatDollarAxis(12_345_678)).toBe('$12.3M');
    });

    it('formats values >= $1k with k suffix rounded to whole number', () => {
        expect(formatDollarAxis(1_500)).toBe('$2k');
        expect(formatDollarAxis(99_500)).toBe('$100k');
    });

    it('formats values < $1k as a rounded dollar amount', () => {
        expect(formatDollarAxis(750)).toBe('$750');
        expect(formatDollarAxis(0)).toBe('$0');
    });

    it('preserves a leading minus for negative values', () => {
        expect(formatDollarAxis(-2_500_000)).toBe('-$2.5M');
        expect(formatDollarAxis(-3_500)).toBe('-$4k');
        expect(formatDollarAxis(-50)).toBe('-$50');
    });
});

describe('formatDollarTooltip', () => {
    it('formats whole-dollar amounts with US grouping commas', () => {
        expect(formatDollarTooltip(1_234_567)).toBe('$1,234,567');
    });

    it('truncates fractional cents to whole dollars', () => {
        expect(formatDollarTooltip(99.99)).toBe('$100');
    });
});

describe('formatPercentAxis', () => {
    it('appends a percent sign', () => {
        expect(formatPercentAxis(5)).toBe('5%');
        expect(formatPercentAxis(0)).toBe('0%');
        expect(formatPercentAxis(-3.5)).toBe('-3.5%');
    });
});
