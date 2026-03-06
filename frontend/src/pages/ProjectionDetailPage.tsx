import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer, ReferenceLine } from 'recharts';
import { getScenario, runProjection } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';
import type { ProjectionResult } from '../types/projection';

export default function ProjectionDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { data: scenario, loading } = useApiQuery(() => getScenario(id!));
    const [result, setResult] = useState<ProjectionResult | null>(null);
    const [running, setRunning] = useState(false);

    async function handleRun() {
        setRunning(true);
        try {
            const data = await runProjection(id!);
            setResult(data);
        } catch {
            toast.error('Failed to run projection');
        } finally {
            setRunning(false);
        }
    }

    if (loading) return <div>Loading...</div>;
    if (!scenario) return <div>Scenario not found</div>;

    const retirementYear = scenario.retirement_date ? new Date(scenario.retirement_date).getFullYear() : null;

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/projections" style={{ color: '#1976d2', textDecoration: 'none' }}>Projections</Link> / {scenario.name}
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>{scenario.name}</h2>
                <button
                    onClick={handleRun}
                    disabled={running}
                    style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                >
                    {running ? 'Running...' : 'Run Projection'}
                </button>
            </div>

            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
                <div style={cardStyle}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Retirement Date</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 600 }}>{scenario.retirement_date}</div>
                </div>
                <div style={cardStyle}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>End Age</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 600 }}>{scenario.end_age}</div>
                </div>
                <div style={cardStyle}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Inflation Rate</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 600 }}>{(scenario.inflation_rate * 100).toFixed(1)}%</div>
                </div>
                <div style={cardStyle}>
                    <div style={{ color: '#666', fontSize: '0.85rem' }}>Accounts</div>
                    <div style={{ fontSize: '1.25rem', fontWeight: 600 }}>{scenario.accounts.length}</div>
                </div>
            </div>

            {scenario.accounts.length > 0 && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Accounts</h3>
                    <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                        <thead>
                            <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Initial Balance</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Annual Contribution</th>
                                <th style={{ textAlign: 'right', padding: '0.5rem' }}>Expected Return</th>
                            </tr>
                        </thead>
                        <tbody>
                            {scenario.accounts.map(a => (
                                <tr key={a.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(a.initial_balance)}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(a.annual_contribution)}</td>
                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{(a.expected_return * 100).toFixed(1)}%</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            {result && (
                <>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
                        <div style={cardStyle}>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Final Balance</div>
                            <div style={{ fontSize: '1.5rem', fontWeight: 600, color: result.final_balance > 0 ? '#2e7d32' : '#d32f2f' }}>
                                {formatCurrency(result.final_balance)}
                            </div>
                        </div>
                        <div style={cardStyle}>
                            <div style={{ color: '#666', fontSize: '0.85rem' }}>Years in Retirement</div>
                            <div style={{ fontSize: '1.5rem', fontWeight: 600 }}>{result.years_in_retirement}</div>
                        </div>
                    </div>

                    <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                        <h3 style={{ marginBottom: '1rem' }}>Balance Over Time</h3>
                        <ResponsiveContainer width="100%" height={400}>
                            <AreaChart data={result.yearly_data} margin={{ top: 5, right: 20, bottom: 5, left: 20 }}>
                                <defs>
                                    <linearGradient id="colorBalance" x1="0" y1="0" x2="0" y2="1">
                                        <stop offset="5%" stopColor="#1976d2" stopOpacity={0.3} />
                                        <stop offset="95%" stopColor="#1976d2" stopOpacity={0} />
                                    </linearGradient>
                                </defs>
                                <XAxis dataKey="year" tick={{ fontSize: 12 }} />
                                <YAxis
                                    tickFormatter={(v: number) => v >= 1000000 ? `$${(v / 1000000).toFixed(1)}M` : `$${(v / 1000).toFixed(0)}k`}
                                    tick={{ fontSize: 12 }}
                                    width={70}
                                />
                                <Tooltip
                                    formatter={(value: number, name: string) => [formatCurrency(value), name === 'end_balance' ? 'Balance' : name]}
                                    labelFormatter={(year: number) => {
                                        const d = result.yearly_data.find(y => y.year === year);
                                        return d ? `${year} (age ${d.age})` : String(year);
                                    }}
                                />
                                {retirementYear && <ReferenceLine x={retirementYear} stroke="#ff9800" strokeDasharray="5 5" label="Retire" />}
                                <Area
                                    type="monotone"
                                    dataKey="end_balance"
                                    stroke="#1976d2"
                                    strokeWidth={2}
                                    fill="url(#colorBalance)"
                                />
                            </AreaChart>
                        </ResponsiveContainer>
                    </div>

                    <div style={cardStyle}>
                        <h3 style={{ marginBottom: '1rem' }}>Year-by-Year Data</h3>
                        <div style={{ maxHeight: '400px', overflow: 'auto' }}>
                            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                <thead>
                                    <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                        <th style={{ textAlign: 'left', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Year</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Age</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Start</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Contributions</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Growth</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Withdrawals</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>End</th>
                                        <th style={{ textAlign: 'center', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Status</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {result.yearly_data.map(y => (
                                        <tr key={y.year} style={{ borderBottom: '1px solid #f0f0f0', background: y.retired ? '#fff8e1' : 'transparent' }}>
                                            <td style={{ padding: '0.5rem' }}>{y.year}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.age}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(y.start_balance)}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.contributions > 0 ? formatCurrency(y.contributions) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: y.growth >= 0 ? '#2e7d32' : '#d32f2f' }}>{formatCurrency(y.growth)}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawals > 0 ? formatCurrency(y.withdrawals) : '-'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right', fontWeight: 600 }}>{formatCurrency(y.end_balance)}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'center' }}>{y.retired ? 'Retired' : 'Working'}</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                </>
            )}
        </div>
    );
}
