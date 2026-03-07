import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listScenarios, compareScenarios } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import { findPeakBalance, findDepletionYear } from '../utils/projectionCalcs';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';
import type { CompareResponse } from '../types/projection';
import {
    AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer,
    Legend, CartesianGrid,
} from 'recharts';

const COLORS = ['#1976d2', '#2e7d32', '#9c27b0'];
const inputStyle = { padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '100%' };
const labelStyle = { display: 'block', marginBottom: '0.25rem', fontWeight: 600 as const, fontSize: '0.85rem' };

export default function ProjectionComparePage() {
    const { data: scenarios, loading } = useApiQuery(listScenarios);
    const [selectedIds, setSelectedIds] = useState<(string | '')[]>(['', '', '']);
    const [result, setResult] = useState<CompareResponse | null>(null);
    const [running, setRunning] = useState(false);

    function handleSelect(index: number, value: string) {
        const next = [...selectedIds];
        next[index] = value;
        setSelectedIds(next);
    }

    async function handleCompare() {
        const ids = selectedIds.filter(id => id !== '');
        if (ids.length < 2) {
            toast.error('Select at least 2 scenarios to compare');
            return;
        }
        setRunning(true);
        try {
            const data = await compareScenarios(ids);
            setResult(data);
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        } finally {
            setRunning(false);
        }
    }

    if (loading) return <div>Loading...</div>;

    const tickFormatter = (v: number) =>
        Math.abs(v) >= 1000000 ? `$${(v / 1000000).toFixed(1)}M` : `$${(v / 1000).toFixed(0)}k`;

    // Build overlay chart data
    const chartData: Record<string, number>[] = [];
    if (result) {
        const maxLen = Math.max(...result.results.map(r => r.yearly_data.length));
        for (let i = 0; i < maxLen; i++) {
            const row: Record<string, number> = {};
            for (let s = 0; s < result.results.length; s++) {
                const yearData = result.results[s].yearly_data[i];
                if (yearData) {
                    row.year = yearData.year;
                    row[`scenario_${s}`] = yearData.end_balance;
                }
            }
            if (row.year) chartData.push(row);
        }
    }

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/projections" style={{ color: '#1976d2', textDecoration: 'none' }}>Projections</Link> / Compare
            </div>

            <h2 style={{ marginBottom: '1.5rem' }}>Compare Scenarios</h2>

            <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr auto', gap: '1rem', alignItems: 'end' }}>
                    {[0, 1, 2].map(i => (
                        <div key={i}>
                            <label style={labelStyle}>Scenario {i + 1}{i < 2 ? ' *' : ' (optional)'}</label>
                            <select style={inputStyle} value={selectedIds[i]} onChange={e => handleSelect(i, e.target.value)}>
                                <option value="">-- Select --</option>
                                {scenarios?.map(s => (
                                    <option key={s.id} value={s.id}>{s.name}</option>
                                ))}
                            </select>
                        </div>
                    ))}
                    <button
                        onClick={handleCompare}
                        disabled={running}
                        style={{ padding: '0.5rem 1.5rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', height: 'fit-content' }}
                    >
                        {running ? 'Comparing...' : 'Compare'}
                    </button>
                </div>
            </div>

            {result && (
                <>
                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Balance Over Time</h3>
                        <ResponsiveContainer width="100%" height={450}>
                            <AreaChart data={chartData} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                                <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
                                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                                <YAxis tickFormatter={tickFormatter} tick={{ fontSize: 12 }} width={70} />
                                <Tooltip
                                    formatter={(value: number, name: string) => {
                                        const idx = parseInt(name.replace('scenario_', ''));
                                        const scenarioName = scenarios?.find(s => s.id === selectedIds.filter(id => id !== '')[idx])?.name || `Scenario ${idx + 1}`;
                                        return [formatCurrency(value), scenarioName];
                                    }}
                                />
                                <Legend formatter={(value: string) => {
                                    const idx = parseInt(value.replace('scenario_', ''));
                                    return scenarios?.find(s => s.id === selectedIds.filter(id => id !== '')[idx])?.name || `Scenario ${idx + 1}`;
                                }} />
                                {result.results.map((_, i) => (
                                    <Area
                                        key={i}
                                        type="monotone"
                                        dataKey={`scenario_${i}`}
                                        stroke={COLORS[i]}
                                        fill={COLORS[i]}
                                        fillOpacity={0.1}
                                        strokeWidth={2}
                                    />
                                ))}
                            </AreaChart>
                        </ResponsiveContainer>
                    </div>

                    <div style={cardStyle}>
                        <h3 style={{ marginBottom: '1rem' }}>Summary</h3>
                        <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                            <thead>
                                <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                    <th style={{ textAlign: 'left', padding: '0.5rem' }}>Metric</th>
                                    {result.results.map((r, i) => {
                                        const name = scenarios?.find(s => s.id === r.scenario_id)?.name || `Scenario ${i + 1}`;
                                        return <th key={i} style={{ textAlign: 'right', padding: '0.5rem', color: COLORS[i] }}>{name}</th>;
                                    })}
                                </tr>
                            </thead>
                            <tbody>
                                <tr style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>Final Balance<br /><span style={{ fontSize: '0.7rem', color: '#999', fontWeight: 'normal' }}>Portfolio value at projection end</span></td>
                                    {result.results.map((r, i) => (
                                        <td key={i} style={{ padding: '0.5rem', textAlign: 'right', fontWeight: 600, color: r.final_balance > 0 ? '#2e7d32' : '#d32f2f' }}>
                                            {formatCurrency(r.final_balance)}
                                        </td>
                                    ))}
                                </tr>
                                <tr style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>Peak Balance<br /><span style={{ fontSize: '0.7rem', color: '#999', fontWeight: 'normal' }}>Highest value reached</span></td>
                                    {result.results.map((r, i) => {
                                        const peak = findPeakBalance(r.yearly_data);
                                        return (
                                            <td key={i} style={{ padding: '0.5rem', textAlign: 'right' }}>
                                                {formatCurrency(peak.balance)} ({peak.year})
                                            </td>
                                        );
                                    })}
                                </tr>
                                <tr style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem' }}>Depletion Year<br /><span style={{ fontSize: '0.7rem', color: '#999', fontWeight: 'normal' }}>Year portfolio reaches $0</span></td>
                                    {result.results.map((r, i) => {
                                        const depletion = findDepletionYear(r.yearly_data);
                                        return (
                                            <td key={i} style={{ padding: '0.5rem', textAlign: 'right', color: depletion ? '#d32f2f' : '#2e7d32' }}>
                                                {depletion ? `${depletion.year} (age ${depletion.age})` : 'Never'}
                                            </td>
                                        );
                                    })}
                                </tr>
                                <tr>
                                    <td style={{ padding: '0.5rem' }}>Years in Retirement<br /><span style={{ fontSize: '0.7rem', color: '#999', fontWeight: 'normal' }}>Retirement to projection end</span></td>
                                    {result.results.map((r, i) => (
                                        <td key={i} style={{ padding: '0.5rem', textAlign: 'right' }}>
                                            {r.years_in_retirement}
                                        </td>
                                    ))}
                                </tr>
                            </tbody>
                        </table>
                    </div>
                </>
            )}
        </div>
    );
}
