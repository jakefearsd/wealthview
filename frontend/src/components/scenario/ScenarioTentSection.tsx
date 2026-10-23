import { useEffect, useState } from 'react';
import { extractErrorMessage, formatPercent, formatWholeCurrency } from '@wealthview/shared';
import FormField from '../FormField';
import HelpText from '../HelpText';
import { inputStyle } from '../../utils/styles';
import { sizeBondTent } from '../../api/projections';
import type {
    BondTentConfig,
    BondTentMix,
    BondTentMixSizing,
    BondTentSizing,
} from '../../types/projection';
import {
    DEFAULT_BUILD_UP_START_YEARS,
    DEFAULT_COVERAGE_YEARS,
    DEFAULT_TENT_MIX,
    DEFAULT_TENT_YIELDS,
    EXAMPLE_FUNDS,
} from './bondTentDefaults';
import { MATURITIES, MAX_MIXES, MAX_NAME_LENGTH, SHARE_TOLERANCE, isTentConfigValid, shareTotal } from './bondTentValidation';

const SIZING_DEBOUNCE_MS = 500;
const COVERAGE_OPTIONS = Array.from({ length: 19 }, (_, i) => 1 + i * 0.5);

export interface ScenarioTentSectionProps {
    /** The tent being edited, or null while it is off. */
    config: BondTentConfig | null;
    onChange: (config: BondTentConfig | null) => void;
    /** False when the Spending Plan is "None": a tent needs a plan to size against. */
    planAvailable: boolean;
    /** Id of the saved scenario, or null for an unsaved one. Sizing runs against the saved scenario. */
    scenarioId: string | null;
}

/** Percent display value to decimal, rounded so 29 -> 0.29 rather than 0.28999999999999998. */
function toFraction(percent: number): number {
    return Math.round(percent * 1e4) / 1e6;
}

function toPercentValue(fraction: number): number {
    return Math.round(fraction * 1e6) / 1e4;
}

function money(value: number): string {
    return formatWholeCurrency(value);
}

function reasonText(mix: BondTentMixSizing): string {
    const amount = money(Math.abs(mix.reason_amount));
    switch (mix.reason) {
        case 'SPENDING_LEVEL':
            return `Your planned spending in these years is ${mix.reason_amount >= 0 ? 'higher' : 'lower'} than 4% of your portfolio.`;
        case 'INCOME':
            return `Income such as Social Security, a pension or rent pays for about ${amount} of these years.`;
        case 'WITHDRAWAL_TAX':
            return `About ${amount} of the withdrawals go to income tax.`;
        case 'MIX_YIELDS':
            return `This mix's yields earn about ${amount} while the tent is spent down.`;
    }
}

function sizingLine(mix: BondTentMixSizing, portfolio: number, prefix: string): string {
    return `${prefix}Tent size needed: ${money(mix.target)} · ${formatPercent(mix.percent_of_portfolio)} `
        + `of your projected ${money(portfolio)} at retirement`;
}

function SizingResult({ sizing }: { sizing: BondTentSizing }) {
    const [planMix, ...otherMixes] = sizing.mixes;
    const portfolio = sizing.projected_portfolio_at_retirement;
    return (
        <div>
            {planMix && (
                <>
                    <div style={{ fontWeight: 600 }}>{sizingLine(planMix, portfolio, '')}</div>
                    {otherMixes.map((mix, i) => (
                        <div key={i}>{sizingLine(mix, portfolio, `${mix.name}: `)}</div>
                    ))}
                    <div style={{ marginTop: '0.5rem', fontWeight: 600 }}>
                        {`Why it differs from the rule of thumb (${money(planMix.rule_of_thumb)})`}
                    </div>
                    <div>{reasonText(planMix)}</div>
                </>
            )}
            <div style={{ marginTop: '0.5rem', fontWeight: 600 }}>Withdrawals the tent covers</div>
            <ul style={{ margin: '0.25rem 0 0', paddingLeft: '1.25rem' }}>
                {sizing.covered_years.map(y => (
                    <li key={y.year}>
                        {`${y.year}: ${money(y.withdrawal)}`}{y.fraction < 1 ? ` (partial year, ${formatPercent(y.fraction, 0)} covered)` : ''}
                    </li>
                ))}
            </ul>
        </div>
    );
}

function SizingBox({ config, scenarioId }: { config: BondTentConfig; scenarioId: string | null }) {
    const [sizing, setSizing] = useState<BondTentSizing | null>(null);
    const [error, setError] = useState<string | null>(null);
    const valid = isTentConfigValid(config);
    const configKey = JSON.stringify(config);

    useEffect(() => {
        if (scenarioId == null || !valid) {
            setSizing(null);
            setError(null);
            return undefined;
        }
        let cancelled = false;
        const timer = setTimeout(() => {
            sizeBondTent(scenarioId, JSON.parse(configKey) as BondTentConfig)
                .then(result => {
                    if (!cancelled) {
                        setSizing(result);
                        setError(null);
                    }
                })
                .catch((err: unknown) => {
                    if (!cancelled) {
                        setSizing(null);
                        setError(extractErrorMessage(err));
                    }
                });
        }, SIZING_DEBOUNCE_MS);
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [scenarioId, valid, configKey]);

    let body;
    if (scenarioId == null) {
        body = <div>Save the scenario to see how big the tent needs to be.</div>;
    } else if (error) {
        body = <div style={{ color: '#d32f2f' }}>{error}</div>;
    } else if (sizing) {
        body = <SizingResult sizing={sizing} />;
    } else {
        body = <div>{valid ? 'Sizing the tent...' : 'Fix the tent settings above to see its size.'}</div>;
    }
    return (
        <div style={{ background: '#f5f5f5', borderRadius: '6px', padding: '0.75rem', fontSize: '0.9rem', marginTop: '0.75rem' }}>
            {body}
        </div>
    );
}

/** Bond tent configuration: coverage, build-up, up to three maturity mixes, yields, and the live tent size. */
export default function ScenarioTentSection({ config, onChange, planAvailable, scenarioId }: ScenarioTentSectionProps) {
    const [selected, setSelected] = useState(0);
    const on = config != null && planAvailable;
    const selectedIndex = config ? Math.min(selected, config.mixes.length - 1) : 0;

    function update(patch: Partial<BondTentConfig>) {
        if (config) onChange({ ...config, ...patch });
    }

    function updateMix(index: number, patch: Partial<BondTentMix>) {
        if (!config) return;
        update({ mixes: config.mixes.map((m, i) => (i === index ? { ...m, ...patch } : m)) });
    }

    function toggle(checked: boolean) {
        onChange(checked
            ? {
                coverage_years: DEFAULT_COVERAGE_YEARS,
                build_up_start_years: DEFAULT_BUILD_UP_START_YEARS,
                mixes: [structuredClone(DEFAULT_TENT_MIX)],
                yields: { ...DEFAULT_TENT_YIELDS },
            }
            : null);
        setSelected(0);
    }

    function addMix() {
        if (!config || config.mixes.length >= MAX_MIXES) return;
        const taken = new Set(config.mixes.map(m => m.name));
        let n = config.mixes.length + 1;
        while (taken.has(`Mix ${n}`)) n++;
        update({ mixes: [...config.mixes, { name: `Mix ${n}`, shares: { ...DEFAULT_TENT_MIX.shares } }] });
        setSelected(config.mixes.length);
    }

    function removeMix(index: number) {
        if (!config) return;
        update({ mixes: config.mixes.filter((_, i) => i !== index) });
        setSelected(0);
    }

    const mix = on && config ? config.mixes[selectedIndex] : null;
    const total = mix ? shareTotal(mix) : 0;
    const totalOk = Math.abs(total - 1) <= SHARE_TOLERANCE;

    return (
        <div style={{ marginBottom: '1rem' }}>
            <h3 style={{ margin: '0 0 0.5rem' }}>Bond tent</h3>
            <label style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                <input
                    type="checkbox"
                    checked={on}
                    disabled={!planAvailable}
                    onChange={e => toggle(e.target.checked)}
                />
                Use a bond tent
            </label>
            {!planAvailable && (
                <HelpText>A bond tent needs a spending plan. Choose a spending profile or an optimized plan above.</HelpText>
            )}

            {on && config && mix && (
                <div style={{ marginTop: '0.75rem' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem' }}>
                        <FormField label="Years of spending to cover">
                            <select
                                style={inputStyle}
                                value={String(config.coverage_years)}
                                onChange={e => update({ coverage_years: Number(e.target.value) })}
                            >
                                {COVERAGE_OPTIONS.map(v => <option key={v} value={String(v)}>{v}</option>)}
                            </select>
                        </FormField>
                        <FormField label="Start building this many years before retirement">
                            <input
                                style={inputStyle}
                                type="number"
                                min={0}
                                max={40}
                                step={1}
                                value={config.build_up_start_years}
                                onChange={e => update({ build_up_start_years: Number(e.target.value) })}
                            />
                        </FormField>
                    </div>

                    <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', margin: '0.75rem 0 0.5rem', flexWrap: 'wrap' }}>
                        {config.mixes.map((m, i) => (
                            <span
                                key={i}
                                style={{
                                    display: 'inline-flex', alignItems: 'center', gap: '0.25rem', padding: '0.2rem 0.5rem',
                                    borderRadius: '999px', border: `1px solid ${i === selectedIndex ? '#1976d2' : '#ccc'}`,
                                }}
                            >
                                <input
                                    aria-label={`Mix ${i + 1} name`}
                                    maxLength={MAX_NAME_LENGTH}
                                    value={m.name}
                                    onFocus={() => setSelected(i)}
                                    onChange={e => updateMix(i, { name: e.target.value })}
                                    style={{ border: 'none', background: 'transparent', width: '8rem' }}
                                />
                                {i === 0 && <span style={{ fontSize: '0.75rem' }}>(plan)</span>}
                                {i > 0 && (
                                    <button
                                        type="button"
                                        aria-label={`Remove mix ${m.name}`}
                                        onClick={() => removeMix(i)}
                                        style={{ border: 'none', background: 'transparent', cursor: 'pointer' }}
                                    >
                                        ×
                                    </button>
                                )}
                            </span>
                        ))}
                        {config.mixes.length < MAX_MIXES && (
                            <button type="button" onClick={addMix}>Add mix</button>
                        )}
                    </div>

                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem' }}>
                        {MATURITIES.map(({ key, label }) => (
                            <FormField key={key} label={`${label} (${EXAMPLE_FUNDS[key]})`}>
                                <input
                                    style={inputStyle}
                                    type="number"
                                    min={0}
                                    max={100}
                                    step={1}
                                    value={toPercentValue(mix.shares[key])}
                                    onChange={e => updateMix(selectedIndex, {
                                        shares: { ...mix.shares, [key]: toFraction(Number(e.target.value)) },
                                    })}
                                />
                            </FormField>
                        ))}
                    </div>
                    <div
                        role={totalOk ? undefined : 'alert'}
                        style={{ fontSize: '0.85rem', marginTop: '0.25rem', color: totalOk ? 'inherit' : '#d32f2f' }}
                    >
                        {`Shares add up to ${toPercentValue(total)}%`}
                    </div>
                    {mix.shares.ultrashort === 1 && (
                        <HelpText>
                            100% ultrashort is a classic cash reserve. Moving part of it into short or intermediate bonds
                            usually earns more, at the cost of some price swings.
                        </HelpText>
                    )}

                    <h4 style={{ margin: '0.75rem 0 0.25rem' }}>Today&apos;s yields</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem' }}>
                        {MATURITIES.map(({ key, label }) => (
                            <FormField key={key} label={`${label} yield (%)`}>
                                <input
                                    style={inputStyle}
                                    type="number"
                                    min={-2}
                                    max={20}
                                    step={0.01}
                                    value={toPercentValue(config.yields[key])}
                                    onChange={e => update({ yields: { ...config.yields, [key]: toFraction(Number(e.target.value)) } })}
                                />
                            </FormField>
                        ))}
                    </div>

                    <HelpText>
                        The tent is drawn only when stocks are below their previous high, shortest bonds first.
                        It is never refilled, and whatever is left stays in bonds.
                    </HelpText>

                    <SizingBox config={config} scenarioId={scenarioId} />
                </div>
            )}
        </div>
    );
}
