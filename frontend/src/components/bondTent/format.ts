import type { BondTentReplay } from '../../types/projection';
import { formatWholeCurrency } from '../../utils/format';

export const DASH = '—';

export function money(value: number | null | undefined): string {
    return value == null ? DASH : formatWholeCurrency(value);
}

export function years(value: number | null | undefined): string {
    return value == null ? DASH : value.toFixed(1);
}

/**
 * True when the replay is anchored on today rather than a future retirement. The server anchors at
 * max(retirement year, current year), so an anchor at or before this year means the user is already
 * retired (or retires this year). Display labels only; the timeline itself is unchanged.
 */
export function replayAnchorIsToday(replay: BondTentReplay): boolean {
    return replay.retirement_year <= new Date().getFullYear();
}
