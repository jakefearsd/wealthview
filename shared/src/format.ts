/** Convert a decimal rate (0.035) to a percentage (3.5), avoiding IEEE 754 noise. */
export function toPercent(decimal: number): number {
    return parseFloat((decimal * 100).toPrecision(10));
}

export function formatCurrency(value: number, currency: string = 'USD'): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency }).format(value);
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
