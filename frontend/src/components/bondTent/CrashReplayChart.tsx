import { useMemo } from 'react';
import {
    LineChart, Line, XAxis, YAxis, Tooltip, Legend, ResponsiveContainer,
    ReferenceLine, ReferenceArea, CartesianGrid,
} from 'recharts';
import type { BondTentReplay } from '../../types/projection';
import { formatDollarAxis } from '../../utils/chartFormatters';
import { formatWholeCurrency } from '../../utils/format';
import { replayAnchorIsToday } from './format';

interface Props {
    replay: BondTentReplay;
}

const COLORS = ['#546e7a', '#1976d2', '#2e7d32', '#ef6c00', '#8e24aa', '#c62828'];

export default function CrashReplayChart({ replay }: Props) {
    const rows = useMemo(() => {
        const byYear = new Map<number, Record<string, number>>();
        // Series are keyed by position, so mixes sharing a name (or named "year") cannot collide.
        replay.outcomes.forEach((outcome, i) => {
            for (const y of outcome.years) {
                const row = byYear.get(y.year) ?? { year: y.year };
                row[`s${i}`] = y.balance;
                byYear.set(y.year, row);
            }
        });
        return [...byYear.values()].sort((a, b) => a.year - b.year);
    }, [replay]);

    const crashEnd = replay.crash_calendar_year + replay.crash_last_year - replay.crash_first_year;
    const historyEnds = replay.outcomes[0]?.years.find(y => y.year > crashEnd && y.history_year === null)?.year;

    // Replay years before the first year of real history run on long-run averages: shade that leading stretch.
    const firstOutcomeYears = replay.outcomes[0]?.years ?? [];
    const firstHistoryIndex = firstOutcomeYears.findIndex(y => y.history_year !== null);
    const preHistoryYears = firstHistoryIndex > 0 ? firstOutcomeYears.slice(0, firstHistoryIndex) : [];
    const anchorLabel = replayAnchorIsToday(replay) ? 'Today' : 'Retire';

    return (
        <ResponsiveContainer width="100%" height={340}>
            <LineChart data={rows} margin={{ top: 20, right: 20, left: 10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="year" type="number" domain={['dataMin', 'dataMax']} />
                <YAxis tickFormatter={formatDollarAxis} />
                <Tooltip formatter={(v: unknown) => formatWholeCurrency(Number(v))} />
                <Legend />
                <ReferenceArea
                    x1={replay.crash_calendar_year - 0.5}
                    x2={crashEnd + 0.5}
                    ifOverflow="hidden"
                    fill="#ef5350"
                    fillOpacity={0.15}
                    label={{ value: replay.crash_label, position: 'insideTop' }}
                />
                {preHistoryYears.length > 0 && (
                    <ReferenceArea
                        x1={preHistoryYears[0].year - 0.5}
                        x2={preHistoryYears[preHistoryYears.length - 1].year + 0.5}
                        ifOverflow="hidden"
                        fill="#9e9e9e"
                        fillOpacity={0.15}
                        label={{ value: 'Long-run averages', position: 'insideTop' }}
                    />
                )}
                <ReferenceLine
                    x={replay.retirement_year}
                    stroke="#555"
                    strokeDasharray="4 4"
                    label={{ value: `${anchorLabel} ${replay.retirement_year}`, position: 'top' }}
                />
                {historyEnds !== undefined && (
                    <ReferenceLine
                        x={historyEnds}
                        stroke="#999"
                        strokeDasharray="2 4"
                        label={{ value: 'History ends', position: 'top' }}
                    />
                )}
                {replay.outcomes.map((o, i) => (
                    <Line
                        key={i}
                        type="monotone"
                        dataKey={`s${i}`}
                        name={o.name}
                        stroke={COLORS[i % COLORS.length]}
                        dot={false}
                        strokeWidth={2}
                    />
                ))}
            </LineChart>
        </ResponsiveContainer>
    );
}
