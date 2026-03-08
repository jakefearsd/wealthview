export function formatCurrency(value: number): string {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(value);
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
    const intPart = parts[0].replace(/,/g, '');
    if (intPart === '' || intPart === '-') return str;
    const formatted = intPart.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    return parts.length > 1 ? `${formatted}.${parts[1]}` : formatted;
}
