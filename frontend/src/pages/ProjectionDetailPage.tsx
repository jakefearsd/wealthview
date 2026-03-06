import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getScenario, runProjection } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import { findPeakBalance, findDepletionYear } from '../utils/projectionCalcs';
import SummaryCard from '../components/SummaryCard';
import ProjectionChart from '../components/ProjectionChart';
import MilestoneStrip from '../components/MilestoneStrip';
import toast from 'react-hot-toast';
import type { ProjectionResult } from '../types/projection';

type TabId = 'chart' | 'flows' | 'table';

const tabButtonStyle = (active: boolean) => ({
    padding: '0.5rem 1rem',
    background: 'none',
    border: 'none',
    borderBottom: `2px solid ${active ? '#1976d2' : 'transparent'}`,
    color: active ? '#1976d2' : '#666',
    fontWeight: active ? 600 as const : 400 as const,
    cursor: 'pointer' as const,
    fontSize: '0.95rem',
});

export default function ProjectionDetailPage() {
    const { id } = useParams<{ id: string }>();
    const { data: scenario, loading } = useApiQuery(() => getScenario(id!));
    const [result, setResult] = useState<ProjectionResult | null>(null);
    const [running, setRunning] = useState(false);
    const [activeTab, setActiveTab] = useState<TabId>('chart');

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
    const peak = result ? findPeakBalance(result.yearly_data) : null;
    const depletion = result ? findDepletionYear(result.yearly_data) : null;

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
                <SummaryCard label="Retirement Date" value={scenario.retirement_date} />
                <SummaryCard label="End Age" value={String(scenario.end_age)} />
                <SummaryCard label="Inflation Rate" value={`${(scenario.inflation_rate * 100).toFixed(1)}%`} />
                <SummaryCard label="Accounts" value={String(scenario.accounts.length)} />
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
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <SummaryCard
                            label="Final Balance"
                            value={formatCurrency(result.final_balance)}
                            valueColor={result.final_balance > 0 ? '#2e7d32' : '#d32f2f'}
                        />
                        <SummaryCard
                            label="Years in Retirement"
                            value={String(result.years_in_retirement)}
                        />
                        <SummaryCard
                            label="Peak Balance"
                            value={formatCurrency(peak!.balance)}
                            subtext={`(year ${peak!.year})`}
                        />
                        <SummaryCard
                            label="Depletion Year"
                            value={depletion ? `${depletion.year} (age ${depletion.age})` : 'Never'}
                            valueColor={depletion ? '#d32f2f' : '#2e7d32'}
                        />
                    </div>

                    <div style={{ marginBottom: '1.5rem' }}>
                        <MilestoneStrip result={result} retirementYear={retirementYear} />
                    </div>

                    <div style={cardStyle}>
                        <div style={{ borderBottom: '1px solid #e0e0e0', marginBottom: '1rem', display: 'flex', gap: '0.25rem' }}>
                            <button style={tabButtonStyle(activeTab === 'chart')} onClick={() => setActiveTab('chart')}>
                                Balance Over Time
                            </button>
                            <button style={tabButtonStyle(activeTab === 'flows')} onClick={() => setActiveTab('flows')}>
                                Annual Flows
                            </button>
                            <button style={tabButtonStyle(activeTab === 'table')} onClick={() => setActiveTab('table')}>
                                Data Table
                            </button>
                        </div>

                        {activeTab === 'chart' && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="balance" />
                        )}

                        {activeTab === 'flows' && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="flows" />
                        )}

                        {activeTab === 'table' && (
                            <div style={{ maxHeight: '450px', overflow: 'auto' }}>
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
                                        {result.yearly_data.map((y, i) => {
                                            const isRetirementTransition = y.retired && i > 0 && !result.yearly_data[i - 1].retired;
                                            return (
                                                <tr
                                                    key={y.year}
                                                    style={{
                                                        borderBottom: '1px solid #f0f0f0',
                                                        borderTop: isRetirementTransition ? '3px solid #ff9800' : undefined,
                                                        background: y.retired ? '#fff8e1' : 'transparent',
                                                    }}
                                                >
                                                    <td style={{ padding: '0.5rem' }}>{y.year}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.age}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(y.start_balance)}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.contributions > 0 ? formatCurrency(y.contributions) : '-'}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: y.growth >= 0 ? '#2e7d32' : '#d32f2f' }}>{formatCurrency(y.growth)}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.withdrawals > 0 ? formatCurrency(y.withdrawals) : '-'}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'right', fontWeight: 600 }}>{formatCurrency(y.end_balance)}</td>
                                                    <td style={{ padding: '0.5rem', textAlign: 'center' }}>{y.retired ? 'Retired' : 'Working'}</td>
                                                </tr>
                                            );
                                        })}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                </>
            )}
        </div>
    );
}
