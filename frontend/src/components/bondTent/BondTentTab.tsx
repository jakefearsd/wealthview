import { useEffect, useState } from 'react';
import { getBondTentReplay, getBondTentSimulation } from '../../api/projections';
import { useApiQuery } from '../../hooks/useApiQuery';
import { CRASHES } from '../../types/projection';
import type { CrashKey } from '../../types/projection';
import { cardStyle, inputStyle, labelStyle, selectStyle } from '../../utils/styles';
import DisclaimerBox from '../DisclaimerBox';
import ErrorState from '../ErrorState';
import LoadingState from '../LoadingState';
import SegmentedControl from '../SegmentedControl';
import CrashReplayChart from './CrashReplayChart';
import { replayAnchorIsToday } from './format';
import CrashReplayTable from './CrashReplayTable';
import TentSimulationStrip from './TentSimulationStrip';

interface Props {
    scenarioId: string;
    hasTent: boolean;
}

const TIMINGS: { value: string; offset: number; label: string }[] = [
    { value: '-3', offset: -3, label: '−3' },
    { value: '-2', offset: -2, label: '−2' },
    { value: '-1', offset: -1, label: '−1' },
    { value: '0', offset: 0, label: 'At retirement' },
    { value: '1', offset: 1, label: '+1' },
    { value: '2', offset: 2, label: '+2' },
    { value: '3', offset: 3, label: '+3' },
];

const DEFAULT_START_YEAR = 2000;
const ANY_START_YEAR = 'any';

export default function BondTentTab({ scenarioId, hasTent }: Props) {
    if (!hasTent) {
        return (
            <p style={{ color: '#555' }}>
                Set up a bond tent in this scenario to compare it with no tent here: edit the scenario and turn on
                Use a bond tent.
            </p>
        );
    }
    return <TentAnalysis scenarioId={scenarioId} />;
}

function TentAnalysis({ scenarioId }: { scenarioId: string }) {
    const sim = useApiQuery(() => getBondTentSimulation(scenarioId), [scenarioId]);

    return (
        <div>
            <p style={{ color: '#555', marginTop: 0 }}>
                The main projection shows what a tent costs: on average returns stocks never fall below their high, so
                the tent is built and held but never drawn. The replays below show what it buys in a real crash, and
                what it costs when markets recover quickly.
            </p>
            {sim.loading && <LoadingState message="Simulating..." />}
            {sim.error && <ErrorState message={sim.error} onRetry={sim.refetch} />}
            {sim.data && (sim.data.trial_count === 0 || sim.data.columns.length === 0
                ? <p style={{ color: '#555' }}>Nothing to simulate: retirement is at or past the end of the plan.</p>
                : <TentSimulationStrip simulation={sim.data} />)}
            <CrashReplaySection scenarioId={scenarioId} />
        </div>
    );
}

function CrashReplaySection({ scenarioId }: { scenarioId: string }) {
    const [crash, setCrash] = useState<CrashKey | typeof ANY_START_YEAR>(CRASHES[0].key);
    const [startYear, setStartYear] = useState(DEFAULT_START_YEAR);
    const [startYearDraft, setStartYearDraft] = useState(String(DEFAULT_START_YEAR));
    const [startYearError, setStartYearError] = useState<string | null>(null);
    const [offset, setOffset] = useState(0);

    const replay = useApiQuery(
        () => getBondTentReplay(scenarioId, crash === ANY_START_YEAR ? { start_year: startYear } : { crash }, offset),
        [scenarioId, crash, startYear, offset],
    );
    const data = replay.data;
    const available = data?.available_offsets;
    const anchorIsToday = data != null && replayAnchorIsToday(data);

    useEffect(() => {
        if (available && available.length > 0 && !available.includes(offset)) {
            setOffset(available[0]);
        }
    }, [available, offset]);

    function onStartYearChange(text: string) {
        setStartYearDraft(text);
        if (!/^\d{4}$/.test(text)) {
            // An incomplete year is not an out-of-range one; don't leave the old complaint on screen.
            setStartYearError(null);
            return;
        }
        const value = Number(text);
        const first = data?.first_start_year;
        const last = data?.last_start_year;
        if (first !== undefined && last !== undefined && (value < first || value > last)) {
            setStartYearError(`Start year must be between ${first} and ${last}`);
            return;
        }
        setStartYearError(null);
        setStartYear(value);
    }

    return (
        <div style={cardStyle}>
            <h3 style={{ marginTop: 0 }}>Historical crash replay</h3>
            <div style={{ display: 'flex', gap: '1.5rem', flexWrap: 'wrap', alignItems: 'flex-end', marginBottom: '1rem' }}>
                <div>
                    <label htmlFor="crash-select" style={labelStyle}>Crash</label>
                    <select
                        id="crash-select"
                        style={selectStyle}
                        value={crash}
                        onChange={e => {
                            setCrash(e.target.value as CrashKey | typeof ANY_START_YEAR);
                            setStartYearError(null);
                        }}
                    >
                        {CRASHES.map(c => <option key={c.key} value={c.key}>{c.label}</option>)}
                        <option value={ANY_START_YEAR}>Any start year</option>
                    </select>
                </div>
                {crash === ANY_START_YEAR && (
                    <div>
                        <label htmlFor="start-year" style={labelStyle}>Start year</label>
                        <input
                            id="start-year"
                            type="number"
                            style={{ ...inputStyle, width: '6rem' }}
                            min={data?.first_start_year}
                            max={data?.last_start_year}
                            value={startYearDraft}
                            onChange={e => onStartYearChange(e.target.value)}
                            aria-invalid={startYearError ? true : undefined}
                        />
                        {startYearError && (
                            <div role="alert" style={{ fontSize: '0.8rem', color: '#d32f2f' }}>{startYearError}</div>
                        )}
                    </div>
                )}
                <div>
                    <span id="crash-hits-label" style={labelStyle}>Crash hits</span>
                    <div role="group" aria-labelledby="crash-hits-label">
                        <SegmentedControl
                            options={TIMINGS.map(t => ({
                                value: t.value,
                                label: t.offset === 0 && anchorIsToday ? 'Today' : t.label,
                                disabled: available !== undefined && !available.includes(t.offset),
                            }))}
                            value={String(offset)}
                            onChange={v => setOffset(Number(v))}
                        />
                    </div>
                </div>
            </div>
            {replay.loading && <LoadingState message="Replaying..." />}
            {replay.error && <ErrorState message={replay.error} onRetry={replay.refetch} />}
            {!replay.loading && !replay.error && data && (
                <>
                    <CrashReplayChart replay={data} />
                    <CrashReplayTable replay={data} />
                </>
            )}
            <DisclaimerBox testId="replay-disclaimer" margin="1rem 0 0">
                Replays use actual inflation-adjusted returns for 1954–2025 and long-run averages outside those
                years. Accounts with a fixed expected return keep it in every replay. Bond yields are the assumptions
                you entered, and taxes are estimates. This is a model, not a forecast.
            </DisclaimerBox>
        </div>
    );
}
