import { formatWholeCurrency } from '../../utils/format';

export const DASH = '—';

export function money(value: number | null | undefined): string {
    return value == null ? DASH : formatWholeCurrency(value);
}

export function years(value: number | null | undefined): string {
    return value == null ? DASH : value.toFixed(1);
}
