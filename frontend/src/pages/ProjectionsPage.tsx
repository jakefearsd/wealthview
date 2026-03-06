import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listScenarios, createScenario, deleteScenario } from '../api/projections';
import { useApiQuery } from '../hooks/useApiQuery';
import { cardStyle } from '../utils/styles';
import toast from 'react-hot-toast';
import type { CreateScenarioRequest } from '../types/projection';

const inputStyle = { padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '100%' };
const labelStyle = { display: 'block', marginBottom: '0.25rem', fontWeight: 600 as const, fontSize: '0.85rem' };

export default function ProjectionsPage() {
    const { data: scenarios, loading, refetch } = useApiQuery(listScenarios);
    const [showForm, setShowForm] = useState(false);
    const [saving, setSaving] = useState(false);

    const [name, setName] = useState('');
    const [retirementDate, setRetirementDate] = useState('');
    const [endAge, setEndAge] = useState(90);
    const [inflationRate, setInflationRate] = useState(0.03);
    const [birthYear, setBirthYear] = useState(1990);
    const [withdrawalRate, setWithdrawalRate] = useState(0.04);
    const [initialBalance, setInitialBalance] = useState(100000);
    const [annualContribution, setAnnualContribution] = useState(10000);
    const [expectedReturn, setExpectedReturn] = useState(0.07);

    async function handleCreate() {
        if (!name || !retirementDate) {
            toast.error('Name and retirement date are required');
            return;
        }
        setSaving(true);
        try {
            const request: CreateScenarioRequest = {
                name,
                retirement_date: retirementDate,
                end_age: endAge,
                inflation_rate: inflationRate,
                birth_year: birthYear,
                withdrawal_rate: withdrawalRate,
                accounts: [{
                    linked_account_id: null,
                    initial_balance: initialBalance,
                    annual_contribution: annualContribution,
                    expected_return: expectedReturn,
                }],
            };
            await createScenario(request);
            toast.success('Scenario created');
            setShowForm(false);
            setName('');
            refetch();
        } catch {
            toast.error('Failed to create scenario');
        } finally {
            setSaving(false);
        }
    }

    async function handleDelete(id: string) {
        try {
            await deleteScenario(id);
            toast.success('Scenario deleted');
            refetch();
        } catch {
            toast.error('Failed to delete scenario');
        }
    }

    if (loading) return <div>Loading...</div>;

    return (
        <div>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '1.5rem' }}>
                <h2>Retirement Projections</h2>
                <button
                    onClick={() => setShowForm(!showForm)}
                    style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                >
                    {showForm ? 'Cancel' : 'New Scenario'}
                </button>
            </div>

            {showForm && (
                <div style={{ ...cardStyle, marginBottom: '1.5rem' }}>
                    <h3 style={{ marginBottom: '1rem' }}>Create Scenario</h3>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Name</label>
                            <input style={inputStyle} value={name} onChange={e => setName(e.target.value)} placeholder="Retirement Plan" />
                        </div>
                        <div>
                            <label style={labelStyle}>Retirement Date</label>
                            <input style={inputStyle} type="date" value={retirementDate} onChange={e => setRetirementDate(e.target.value)} />
                        </div>
                        <div>
                            <label style={labelStyle}>Birth Year</label>
                            <input style={inputStyle} type="number" value={birthYear} onChange={e => setBirthYear(Number(e.target.value))} />
                        </div>
                        <div>
                            <label style={labelStyle}>End Age</label>
                            <input style={inputStyle} type="number" value={endAge} onChange={e => setEndAge(Number(e.target.value))} />
                        </div>
                        <div>
                            <label style={labelStyle}>Inflation Rate</label>
                            <input style={inputStyle} type="number" step="0.01" value={inflationRate} onChange={e => setInflationRate(Number(e.target.value))} />
                        </div>
                        <div>
                            <label style={labelStyle}>Withdrawal Rate</label>
                            <input style={inputStyle} type="number" step="0.01" value={withdrawalRate} onChange={e => setWithdrawalRate(Number(e.target.value))} />
                        </div>
                    </div>
                    <h4 style={{ marginBottom: '0.5rem' }}>Account</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Initial Balance</label>
                            <input style={inputStyle} type="number" value={initialBalance} onChange={e => setInitialBalance(Number(e.target.value))} />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Contribution</label>
                            <input style={inputStyle} type="number" value={annualContribution} onChange={e => setAnnualContribution(Number(e.target.value))} />
                        </div>
                        <div>
                            <label style={labelStyle}>Expected Return</label>
                            <input style={inputStyle} type="number" step="0.01" value={expectedReturn} onChange={e => setExpectedReturn(Number(e.target.value))} />
                        </div>
                    </div>
                    <button
                        onClick={handleCreate}
                        disabled={saving}
                        style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        {saving ? 'Creating...' : 'Create Scenario'}
                    </button>
                </div>
            )}

            <div style={cardStyle}>
                <table style={{ width: '100%', borderCollapse: 'collapse' }}>
                    <thead>
                        <tr style={{ borderBottom: '2px solid #e0e0e0' }}>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Name</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Retirement Date</th>
                            <th style={{ textAlign: 'right', padding: '0.5rem' }}>End Age</th>
                            <th style={{ textAlign: 'right', padding: '0.5rem' }}>Inflation</th>
                            <th style={{ textAlign: 'left', padding: '0.5rem' }}>Created</th>
                            <th style={{ padding: '0.5rem' }}></th>
                        </tr>
                    </thead>
                    <tbody>
                        {scenarios?.map(s => (
                            <tr key={s.id} style={{ borderBottom: '1px solid #f0f0f0' }}>
                                <td style={{ padding: '0.5rem' }}>
                                    <Link to={`/projections/${s.id}`} style={{ color: '#1976d2', textDecoration: 'none' }}>{s.name}</Link>
                                </td>
                                <td style={{ padding: '0.5rem' }}>{s.retirement_date}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{s.end_age}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'right' }}>{(s.inflation_rate * 100).toFixed(1)}%</td>
                                <td style={{ padding: '0.5rem' }}>{new Date(s.created_at).toLocaleDateString()}</td>
                                <td style={{ padding: '0.5rem', textAlign: 'center' }}>
                                    <button onClick={() => handleDelete(s.id)} style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer' }}>Delete</button>
                                </td>
                            </tr>
                        ))}
                        {scenarios?.length === 0 && (
                            <tr><td colSpan={6} style={{ padding: '1rem', color: '#999', textAlign: 'center' }}>No scenarios yet. Create one to get started.</td></tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}
