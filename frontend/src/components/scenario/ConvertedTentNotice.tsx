import type { BondTentConfig } from '../../types/projection';
import HelpText from '../HelpText';

/** One-time notice for a tent the cash-reserve migration created; gone after the scenario's next save. */
export default function ConvertedTentNotice({ tent }: { tent: BondTentConfig | null }) {
    if (!tent?.converted_from_cash_reserve) return null;
    const years = tent.coverage_years;
    return (
        <HelpText>
            {`Your ${years}-year cash reserve is now a ${years}-year bond tent, held entirely in ultrashort `
                + 'Treasuries (the same thing as a money market fund). It works the same way with one difference: '
                + "once spent, it isn't refilled, so results may shift slightly from before."}
        </HelpText>
    );
}
