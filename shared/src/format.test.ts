import { describe, it, expect } from 'vitest';
import {
    toPercent,
    formatCurrency,
    parseCurrencyInput,
    formatCurrencyInput,
    formatWholeCurrency,
    formatCompactCurrency,
    formatPercent,
} from './format';

describe('toPercent', () => {
    it('multiplies by 100 and clips IEEE 754 noise', () => {
        expect(toPercent(0.035)).toBe(3.5);
        // 0.1 * 100 in raw IEEE 754 produces 10.000000000000002.
        expect(toPercent(0.1)).toBe(10);
    });

    it('handles zero and negative rates', () => {
        expect(toPercent(0)).toBe(0);
        expect(toPercent(-0.025)).toBe(-2.5);
    });
});

describe('formatCurrency', () => {
    it('defaults to USD with the standard locale', () => {
        expect(formatCurrency(1234.5)).toBe('$1,234.50');
    });

    it('honours an explicit currency code', () => {
        // Stable across icu / node by checking only the symbol substring.
        expect(formatCurrency(1000, 'EUR')).toContain('1,000.00');
        expect(formatCurrency(1000, 'EUR')).toMatch(/€/);
    });
});

describe('parseCurrencyInput', () => {
    it('strips grouping commas', () => {
        expect(parseCurrencyInput('1,234.56')).toBe('1234.56');
        expect(parseCurrencyInput('1,000,000')).toBe('1000000');
    });

    it('returns input unchanged when there are no commas', () => {
        expect(parseCurrencyInput('123')).toBe('123');
        expect(parseCurrencyInput('')).toBe('');
    });
});

describe('formatCurrencyInput', () => {
    it('returns the empty / minus stub strings unchanged', () => {
        expect(formatCurrencyInput('')).toBe('');
        expect(formatCurrencyInput('-')).toBe('-');
    });

    it('inserts grouping commas on whole numbers', () => {
        expect(formatCurrencyInput('1234')).toBe('1,234');
        expect(formatCurrencyInput('1234567')).toBe('1,234,567');
        expect(formatCurrencyInput(987654321)).toBe('987,654,321');
    });

    it('preserves up to two decimal places', () => {
        expect(formatCurrencyInput('1234.5')).toBe('1,234.5');
        expect(formatCurrencyInput('1234.56')).toBe('1,234.56');
    });

    it('truncates more than two decimal places to two', () => {
        expect(formatCurrencyInput('100.999')).toBe('100.99');
    });

    it('strips leading zeros from positive numbers', () => {
        expect(formatCurrencyInput('001234')).toBe('1,234');
        expect(formatCurrencyInput('0')).toBe('0');
    });

    it('keeps a single zero rather than collapsing to empty', () => {
        // "00" -> after strip leading zeros becomes "" -> fallback "0".
        expect(formatCurrencyInput('00')).toBe('0');
    });

    it('handles negative numbers and strips leading zeros after the minus', () => {
        expect(formatCurrencyInput('-1234')).toBe('-1,234');
        expect(formatCurrencyInput('-001234')).toBe('-1,234');
        expect(formatCurrencyInput('-0')).toBe('-0');
    });

    it('re-formats input that already contained commas', () => {
        expect(formatCurrencyInput('1,234,567')).toBe('1,234,567');
    });
});

describe('formatWholeCurrency', () => {
    it('formats as USD with no fraction digits, rounding to the nearest dollar', () => {
        expect(formatWholeCurrency(1234.56)).toBe('$1,235');
        expect(formatWholeCurrency(1000000)).toBe('$1,000,000');
        expect(formatWholeCurrency(0)).toBe('$0');
    });

    it('keeps the sign on negative values', () => {
        expect(formatWholeCurrency(-1234.56)).toBe('-$1,235');
    });

    it('returns a placeholder for null and undefined', () => {
        expect(formatWholeCurrency(null)).toBe('--');
        expect(formatWholeCurrency(undefined)).toBe('--');
    });
});

describe('formatCompactCurrency', () => {
    it('formats millions with one decimal place', () => {
        expect(formatCompactCurrency(1_500_000)).toBe('$1.5M');
        expect(formatCompactCurrency(1_000_000)).toBe('$1.0M');
    });

    it('formats thousands with no decimal places', () => {
        expect(formatCompactCurrency(235_000)).toBe('$235k');
        expect(formatCompactCurrency(1_000)).toBe('$1k');
        expect(formatCompactCurrency(1_499)).toBe('$1k');
    });

    it('formats values below one thousand as whole dollars', () => {
        expect(formatCompactCurrency(980)).toBe('$980');
        expect(formatCompactCurrency(0)).toBe('$0');
        expect(formatCompactCurrency(12.7)).toBe('$13');
    });

    it('keeps the sign on negative values at every threshold', () => {
        expect(formatCompactCurrency(-1_500_000)).toBe('-$1.5M');
        expect(formatCompactCurrency(-235_000)).toBe('-$235k');
        expect(formatCompactCurrency(-980)).toBe('-$980');
    });

    it('returns a placeholder for null and undefined', () => {
        expect(formatCompactCurrency(null)).toBe('--');
        expect(formatCompactCurrency(undefined)).toBe('--');
    });
});

describe('formatPercent', () => {
    it('renders a 0-1 fraction as a percentage with one decimal by default', () => {
        expect(formatPercent(0.37)).toBe('37.0%');
        expect(formatPercent(0)).toBe('0.0%');
        expect(formatPercent(1)).toBe('100.0%');
    });

    it('honours an explicit digits argument', () => {
        expect(formatPercent(0.375, 0)).toBe('38%');
        expect(formatPercent(0.12345, 2)).toBe('12.35%');
    });

    it('keeps the sign on negative fractions', () => {
        expect(formatPercent(-0.05)).toBe('-5.0%');
    });

    it('returns a placeholder for null and undefined', () => {
        expect(formatPercent(null)).toBe('--');
        expect(formatPercent(undefined)).toBe('--');
    });
});
