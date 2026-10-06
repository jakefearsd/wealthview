/** Convert a decimal rate (0.035) to a percentage (3.5), avoiding IEEE 754 noise. */
export function toPercent(decimal: number): number {
    return parseFloat((decimal * 100).toPrecision(10));
}

export function formatCurrency(value: number, currency: string = 'USD'): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(value);
}

const NULLISH_PLACEHOLDER = '--';

/** Format as USD with no fraction digits: 1234.56 -> "$1,235". Nullish values render as "--". */
export function formatWholeCurrency(value: number | null | undefined): string {
    if (value == null) return NULLISH_PLACEHOLDER;
    return new Intl.NumberFormat('en-US', {
        style: 'currency',
        currency: 'USD',
        maximumFractionDigits: 0,
    }).format(value);
}

/**
 * Compact USD: >= $1M -> one-decimal "M" ($1.5M), >= $1k -> whole "k" ($235k),
 * below -> whole dollars ($980). Negative values keep the sign ("-$1.5M").
 * Nullish values render as "--".
 */
export function formatCompactCurrency(value: number | null | undefined): string {
    if (value == null) return NULLISH_PLACEHOLDER;
    const abs = Math.abs(value);
    const sign = value < 0 ? '-' : '';
    if (abs >= 1_000_000) return `${sign}$${(abs / 1_000_000).toFixed(1)}M`;
    if (abs >= 1_000) return `${sign}$${Math.round(abs / 1_000)}k`;
    return `${sign}$${Math.round(abs)}`;
}

/** Format a 0-1 fraction as a percentage: 0.37 -> "37.0%". Nullish values render as "--". */
export function formatPercent(value: number | null | undefined, digits: number = 1): string {
    if (value == null) return NULLISH_PLACEHOLDER;
    return `${(value * 100).toFixed(digits)}%`;
}

/** Strip commas from a display string to get a raw numeric string. */
export function parseCurrencyInput(display: string): string {
    return display.replace(/,/g, '');
}

/** Format a raw numeric value (string or number) with commas for display in an input field. */
export function formatCurrencyInput(value: string | number): string {
    const str = String(value);
    if (str === '' || str === '-') return str;
    const parts = str.split('.');
    let intPart = parts[0].replace(/,/g, '');
    if (intPart === '' || intPart === '-') return str;
    // Strip leading zeros (but keep a single "0")
    if (intPart.length > 1 && intPart[0] !== '-') {
        intPart = intPart.replace(/^0+/, '') || '0';
    } else if (intPart.length > 2 && intPart[0] === '-') {
        intPart = '-' + (intPart.slice(1).replace(/^0+/, '') || '0');
    }
    const formatted = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    if (parts.length > 1) {
        const dec = parts[1].length > 2 ? parts[1].slice(0, 2) : parts[1];
        return `${formatted}.${dec}`;
    }
    return formatted;
}

const ISO_DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/;

function localIsoDate(date: Date): string {
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${date.getFullYear()}-${month}-${day}`;
}

/**
 * Year of an ISO date string ("2030-01-01" -> 2030). Reads the digits directly because
 * `new Date('2030-01-01')` is UTC midnight, which is still 2029 in any timezone west of UTC.
 */
export function yearOf(isoDate: string): number {
    return Number(isoDate.slice(0, 4));
}

/**
 * Display a date as YYYY-MM-DD. Date-only strings pass through untouched (no UTC shift);
 * timestamps and Date objects render as the local calendar date. Nullish or unparseable
 * values render as "--".
 */
export function formatDate(value: string | Date | null | undefined): string {
    if (value == null) return NULLISH_PLACEHOLDER;
    if (typeof value === 'string' && ISO_DATE_ONLY.test(value)) return value;
    const date = typeof value === 'string' ? new Date(value) : value;
    return Number.isNaN(date.getTime()) ? NULLISH_PLACEHOLDER : localIsoDate(date);
}

/** Today's local calendar date as YYYY-MM-DD (unlike `toISOString()`, which is the UTC date). */
export function todayIso(now: Date = new Date()): string {
    return localIsoDate(now);
}
