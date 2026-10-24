import type { BondTentReplay, BondTentReplayOutcome } from '../../types/projection';
import { tableStyle, thStyle, tdStyle } from '../../utils/styles';
import { money, years } from './format';

interface Props {
    replay: BondTentReplay;
}

export default function CrashReplayTable({ replay }: Props) {
    const rows: { label: string; value: (o: BondTentReplayOutcome) => string }[] = [
        { label: 'Lowest balance', value: o => money(o.lowest_balance) },
        { label: 'Stocks sold while down', value: o => money(o.stocks_sold_while_down) },
        { label: `Years covered (planned ${replay.planned_years})`, value: o => years(o.years_covered) },
        { label: `Tent left over at ${replay.checkpoint_age}`, value: o => money(o.tent_left_at_checkpoint) },
        { label: 'Balance at 90', value: o => money(o.balance_at90) },
    ];
    return (
        <div style={{ overflowX: 'auto' }}>
            <table style={tableStyle}>
                <thead>
                    <tr>
                        <th style={thStyle} />
                        {replay.outcomes.map(o => (
                            <th key={o.name} style={{ ...thStyle, textAlign: 'right' }}>{o.name}</th>
                        ))}
                    </tr>
                </thead>
                <tbody>
                    {rows.map(r => (
                        <tr key={r.label}>
                            <th scope="row" style={{ ...tdStyle, textAlign: 'left', fontWeight: 400 }}>{r.label}</th>
                            {replay.outcomes.map(o => (
                                <td key={o.name} style={{ ...tdStyle, textAlign: 'right' }}>{r.value(o)}</td>
                            ))}
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}
