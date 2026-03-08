import { useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { getScenario, runProjection, updateScenario } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { formatCurrency } from '../utils/format';
import { cardStyle } from '../utils/styles';
import { findPeakBalance, findDepletionYear } from '../utils/projectionCalcs';
import SummaryCard from '../components/SummaryCard';
import ProjectionChart from '../components/ProjectionChart';
import MilestoneStrip from '../components/MilestoneStrip';
import ScenarioForm from '../components/ScenarioForm';
import toast from 'react-hot-toast';
import { extractErrorMessage } from '../utils/errorMessage';
import type { ProjectionResult, CreateScenarioRequest } from '../types/projection';

type TabId = 'chart' | 'flows' | 'table' | 'spending';

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
    const { data: scenario, loading, refetch } = useApiQuery(() => getScenario(id!));
    const [result, setResult] = useState<ProjectionResult | null>(null);
    const [running, setRunning] = useState(false);
    const [activeTab, setActiveTab] = useState<TabId>('chart');
    const [editing, setEditing] = useState(false);

    async function handleRun() {
        setRunning(true);
        try {
            const data = await runProjection(id!);
            setResult(data);
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        } finally {
            setRunning(false);
        }
    }

    async function handleUpdate(data: CreateScenarioRequest) {
        try {
            await updateScenario(id!, data);
            toast.success('Scenario updated');
            setEditing(false);
            refetch();
            setRunning(true);
            try {
                const res = await runProjection(id!);
                setResult(res);
            } finally {
                setRunning(false);
            }
        } catch (err: unknown) {
            toast.error(extractErrorMessage(err));
        }
    }

    if (loading) return <div>Loading...</div>;
    if (!scenario) return <div>Scenario not found</div>;

    const retirementYear = scenario.retirement_date ? new Date(scenario.retirement_date).getFullYear() : null;
    const hasPoolData = result?.yearly_data.some(y => y.traditional_balance !== null) ?? false;
    const hasSpendingData = result?.yearly_data.some(y => y.essential_expenses !== null) ?? false;
    const parsedParams = scenario.params_json ? JSON.parse(scenario.params_json) : {};
    const strategyLabels: Record<string, string> = {
        fixed_percentage: 'Fixed Percentage',
        dynamic_percentage: 'Dynamic Percentage',
        vanguard_dynamic_spending: 'Vanguard Dynamic Spending',
    };
    const strategyLabel = strategyLabels[parsedParams.withdrawal_strategy] || 'Fixed Percentage';
    const peak = result ? findPeakBalance(result.yearly_data) : null;
    const depletion = result ? findDepletionYear(result.yearly_data) : null;
    const feasibility = result?.spending_feasibility ?? null;

    return (
        <div>
            <div style={{ marginBottom: '1.5rem' }}>
                <Link to="/projections" style={{ color: '#1976d2', textDecoration: 'none' }}>Projections</Link> / {scenario.name}
            </div>

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>{scenario.name}</h2>
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <button
                        onClick={() => setEditing(!editing)}
                        style={{ padding: '0.5rem 1rem', background: editing ? '#757575' : '#ff9800', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        {editing ? 'Cancel Edit' : 'Edit'}
                    </button>
                    {!editing && (
                        <button
                            onClick={handleRun}
                            disabled={running}
                            style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                        >
                            {running ? 'Running...' : 'Run Projection'}
                        </button>
                    )}
                </div>
            </div>

            {editing ? (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Edit Scenario</h3>
                    <ScenarioForm initialValues={scenario} onSubmit={handleUpdate} submitLabel="Save & Re-run" />
                </div>
            ) : (
                <>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1.5rem' }}>
                        <SummaryCard label="Retirement Date" value={scenario.retirement_date} />
                        <SummaryCard label="End Age" value={String(scenario.end_age)} />
                        <SummaryCard label="Inflation Rate" value={`${(scenario.inflation_rate * 100).toFixed(1)}%`} />
                        <SummaryCard label="Strategy" value={strategyLabel} />
                        <SummaryCard label="Accounts" value={String(scenario.accounts.length)} />
                        {scenario.spending_profile && (
                            <SummaryCard label="Spending Profile" value={scenario.spending_profile.name} />
                        )}
                    </div>

                    {scenario.accounts.length > 0 && (
                        <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                            <h3 style={{ marginBottom: '1rem' }}>Accounts</h3>
                            <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                                <thead>
                                    <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                                        <th style={{ textAlign: 'left', padding: '0.5rem' }}>Type</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem' }}>Initial Balance</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem' }}>Annual Contribution</th>
                                        <th style={{ textAlign: 'right', padding: '0.5rem' }}>Expected Return</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {scenario.accounts.map(a => (
                                        <tr key={a.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                            <td style={{ padding: '0.5rem', textTransform: 'capitalize' }}>{a.account_type || 'taxable'}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(a.initial_balance)}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{formatCurrency(a.annual_contribution)}</td>
                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{(a.expected_return * 100).toFixed(1)}%</td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    )}
                </>
            )}

            {result && (
                <>
                    {feasibility && !feasibility.spending_feasible && (
                        <div style={{
                            background: '#fff3e0', borderLeft: '4px solid #e65100', padding: '1rem',
                            marginBottom: '1rem', borderRadius: '4px',
                        }}>
                            <strong>Spending Shortfall Detected</strong>
                            <div style={{ marginTop: '0.5rem', fontSize: '0.9rem', color: '#333' }}>
                                Your spending plan requires {formatCurrency(feasibility.required_annual_spending)}/yr
                                but your portfolio can sustain approximately {formatCurrency(feasibility.sustainable_annual_spending)}/yr.
                                {feasibility.first_shortfall_age != null && (
                                    <> Shortfall begins at age {feasibility.first_shortfall_age}.</>
                                )}
                                {' '}Review the Spending Analysis tab for year-by-year details.
                            </div>
                        </div>
                    )}

                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '1rem', marginBottom: '1rem' }}>
                        <SummaryCard
                            label="Final Balance"
                            value={formatCurrency(result.final_balance)}
                            valueColor={result.final_balance > 0 ? '#2e7d32' : '#d32f2f'}
                            description="Portfolio value at the end of your projection period."
                        />
                        <SummaryCard
                            label="Years in Retirement"
                            value={String(result.years_in_retirement)}
                            description="Years between your retirement date and projection end."
                        />
                        <SummaryCard
                            label="Peak Balance"
                            value={formatCurrency(peak!.balance)}
                            subtext={`(year ${peak!.year})`}
                            description="Highest portfolio value reached during the projection."
                        />
                        {(() => {
                            const hasProfile = feasibility !== null;
                            const depletes = depletion !== null;
                            let outcomeLabel: string, outcomeValue: string, outcomeColor: string, outcomeDesc: string;

                            if (!hasProfile) {
                                outcomeLabel = "Depletion Year";
                                outcomeValue = depletes ? `${depletion.year} (age ${depletion.age})` : "Never";
                                outcomeColor = depletes ? '#d32f2f' : '#2e7d32';
                                outcomeDesc = depletes
                                    ? "The year your portfolio reaches $0."
                                    : "Your money outlasts the plan.";
                            } else if (depletes) {
                                outcomeLabel = "Plan Outcome";
                                outcomeValue = `Depleted at age ${depletion.age}`;
                                outcomeColor = '#d32f2f';
                                outcomeDesc = `Portfolio reaches $0 in ${depletion.year}.`;
                            } else if (feasibility.spending_feasible) {
                                outcomeLabel = "Plan Outcome";
                                outcomeValue = "Fully Sustainable";
                                outcomeColor = '#2e7d32';
                                outcomeDesc = `Sustains ${formatCurrency(feasibility.sustainable_annual_spending)}/yr; plan requires ${formatCurrency(feasibility.required_annual_spending)}/yr`;
                            } else {
                                outcomeLabel = "Plan Outcome";
                                outcomeValue = `Underfunded at age ${feasibility.first_shortfall_age}`;
                                outcomeColor = '#d32f2f';
                                outcomeDesc = `Sustains ${formatCurrency(feasibility.sustainable_annual_spending)}/yr of ${formatCurrency(feasibility.required_annual_spending)}/yr needed`;
                            }

                            return (
                                <SummaryCard
                                    label={outcomeLabel}
                                    value={outcomeValue}
                                    valueColor={outcomeColor}
                                    description={outcomeDesc}
                                />
                            );
                        })()}
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
                            {hasSpendingData && (
                                <button style={tabButtonStyle(activeTab === 'spending')} onClick={() => setActiveTab('spending')}>
                                    Spending Analysis
                                </button>
                            )}
                        </div>

                        {activeTab === 'chart' && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="balance" />
                        )}

                        {activeTab === 'flows' && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="flows" />
                        )}

                        {activeTab === 'spending' && hasSpendingData && (
                            <ProjectionChart data={result.yearly_data} retirementYear={retirementYear} mode="spending" />
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
                                            {hasPoolData && (
                                                <>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Traditional</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Roth</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Taxable</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Conversion</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Tax</th>
                                                </>
                                            )}
                                            {hasSpendingData && (
                                                <>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Essential</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Discretionary</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Income</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Net Need</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Surplus/Deficit</th>
                                                    <th style={{ textAlign: 'right', padding: '0.5rem', position: 'sticky', top: 0, background: '#fff' }}>Disc. After Cuts</th>
                                                </>
                                            )}
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
                                                    {hasPoolData && (
                                                        <>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#e65100' }}>{y.traditional_balance != null ? formatCurrency(y.traditional_balance) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.roth_balance != null ? formatCurrency(y.roth_balance) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#1976d2' }}>{y.taxable_balance != null ? formatCurrency(y.taxable_balance) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.roth_conversion_amount ? formatCurrency(y.roth_conversion_amount) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#d32f2f' }}>{y.tax_liability ? formatCurrency(y.tax_liability) : '-'}</td>
                                                        </>
                                                    )}
                                                    {hasSpendingData && (
                                                        <>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.essential_expenses != null ? formatCurrency(y.essential_expenses) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.discretionary_expenses != null ? formatCurrency(y.discretionary_expenses) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#2e7d32' }}>{y.income_streams_total != null ? formatCurrency(y.income_streams_total) : '-'}</td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right' }}>{y.net_spending_need != null ? formatCurrency(y.net_spending_need) : '-'}</td>
                                                            <td style={{
                                                                padding: '0.5rem', textAlign: 'right',
                                                                color: y.spending_surplus != null ? (y.spending_surplus >= 0 ? '#2e7d32' : '#d32f2f') : undefined,
                                                                fontWeight: 600,
                                                            }}>
                                                                {y.spending_surplus != null ? formatCurrency(y.spending_surplus) : '-'}
                                                            </td>
                                                            <td style={{ padding: '0.5rem', textAlign: 'right', color: '#ff9800' }}>{y.discretionary_after_cuts != null ? formatCurrency(y.discretionary_after_cuts) : '-'}</td>
                                                        </>
                                                    )}
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
