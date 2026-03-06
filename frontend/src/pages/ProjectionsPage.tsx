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
    const [withdrawalStrategy, setWithdrawalStrategy] = useState('fixed_percentage');
    const [dynamicCeiling, setDynamicCeiling] = useState(0.05);
    const [dynamicFloor, setDynamicFloor] = useState(-0.025);
    const [accountType, setAccountType] = useState('taxable');
    const [filingStatus, setFilingStatus] = useState('single');
    const [otherIncome, setOtherIncome] = useState(0);
    const [annualRothConversion, setAnnualRothConversion] = useState(0);
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
                withdrawal_strategy: withdrawalStrategy,
                dynamic_ceiling: withdrawalStrategy === 'vanguard_dynamic_spending' ? dynamicCeiling : null,
                dynamic_floor: withdrawalStrategy === 'vanguard_dynamic_spending' ? dynamicFloor : null,
                filing_status: annualRothConversion > 0 ? filingStatus : null,
                other_income: annualRothConversion > 0 ? otherIncome : null,
                annual_roth_conversion: annualRothConversion > 0 ? annualRothConversion : null,
                accounts: [{
                    linked_account_id: null,
                    initial_balance: initialBalance,
                    annual_contribution: annualContribution,
                    expected_return: expectedReturn,
                    account_type: accountType,
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
                <div style={{ display: 'flex', gap: '0.5rem' }}>
                    <Link
                        to="/projections/compare"
                        style={{ padding: '0.5rem 1rem', background: '#9c27b0', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', textDecoration: 'none', fontSize: '0.9rem', display: 'flex', alignItems: 'center' }}
                    >
                        Compare Scenarios
                    </Link>
                    <button
                        onClick={() => setShowForm(!showForm)}
                        style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer' }}
                    >
                        {showForm ? 'Cancel' : 'New Scenario'}
                    </button>
                </div>
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
                        <div>
                            <label style={labelStyle}>Withdrawal Strategy</label>
                            <select style={inputStyle} value={withdrawalStrategy} onChange={e => setWithdrawalStrategy(e.target.value)}>
                                <option value="fixed_percentage">Fixed Percentage (4% Rule)</option>
                                <option value="dynamic_percentage">Dynamic Percentage</option>
                                <option value="vanguard_dynamic_spending">Vanguard Dynamic Spending</option>
                            </select>
                        </div>
                        {withdrawalStrategy === 'vanguard_dynamic_spending' && (
                            <>
                                <div>
                                    <label style={labelStyle}>Ceiling (max increase)</label>
                                    <input style={inputStyle} type="number" step="0.01" value={dynamicCeiling} onChange={e => setDynamicCeiling(Number(e.target.value))} />
                                </div>
                                <div>
                                    <label style={labelStyle}>Floor (max decrease)</label>
                                    <input style={inputStyle} type="number" step="0.01" value={dynamicFloor} onChange={e => setDynamicFloor(Number(e.target.value))} />
                                </div>
                            </>
                        )}
                    </div>
                    <h4 style={{ marginBottom: '0.5rem' }}>Roth Conversion</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Annual Roth Conversion</label>
                            <input style={inputStyle} type="number" value={annualRothConversion} onChange={e => setAnnualRothConversion(Number(e.target.value))} />
                        </div>
                        {annualRothConversion > 0 && (
                            <>
                                <div>
                                    <label style={labelStyle}>Filing Status</label>
                                    <select style={inputStyle} value={filingStatus} onChange={e => setFilingStatus(e.target.value)}>
                                        <option value="single">Single</option>
                                        <option value="married_filing_jointly">Married Filing Jointly</option>
                                    </select>
                                </div>
                                <div>
                                    <label style={labelStyle}>Other Income</label>
                                    <input style={inputStyle} type="number" value={otherIncome} onChange={e => setOtherIncome(Number(e.target.value))} />
                                </div>
                            </>
                        )}
                    </div>
                    <h4 style={{ marginBottom: '0.5rem' }}>Account</h4>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                        <div>
                            <label style={labelStyle}>Account Type</label>
                            <select style={inputStyle} value={accountType} onChange={e => setAccountType(e.target.value)}>
                                <option value="taxable">Taxable</option>
                                <option value="traditional">Traditional (Pre-tax)</option>
                                <option value="roth">Roth</option>
                            </select>
                        </div>
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

            {scenarios?.length === 0 ? (
                <div style={{ ...cardStyle, textAlign: 'center', padding: '3rem' }}>
                    <div style={{ color: '#999', fontSize: '1.1rem' }}>No scenarios yet. Create one to get started.</div>
                </div>
            ) : (
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(340px, 1fr))', gap: '1rem' }}>
                    {scenarios?.map(s => (
                        <div key={s.id} style={cardStyle}>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.75rem' }}>
                                <Link
                                    to={`/projections/${s.id}`}
                                    style={{ color: '#1976d2', textDecoration: 'none', fontWeight: 600, fontSize: '1.1rem' }}
                                >
                                    {s.name}
                                </Link>
                                <button
                                    onClick={() => handleDelete(s.id)}
                                    style={{ background: 'none', border: 'none', color: '#d32f2f', cursor: 'pointer', fontSize: '0.85rem', padding: '0' }}
                                >
                                    Delete
                                </button>
                            </div>
                            <div style={{ display: 'flex', gap: '1.5rem', marginBottom: '0.75rem', fontSize: '0.9rem', color: '#444' }}>
                                <div><span style={{ color: '#999' }}>Retire:</span> {s.retirement_date}</div>
                                <div><span style={{ color: '#999' }}>End Age:</span> {s.end_age}</div>
                                <div><span style={{ color: '#999' }}>Inflation:</span> {(s.inflation_rate * 100).toFixed(1)}%</div>
                            </div>
                            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', fontSize: '0.8rem', color: '#999' }}>
                                <span>Created {new Date(s.created_at).toLocaleDateString()}</span>
                                <Link
                                    to={`/projections/${s.id}`}
                                    style={{ color: '#1976d2', textDecoration: 'none', fontWeight: 500 }}
                                >
                                    View Details &rarr;
                                </Link>
                            </div>
                        </div>
                    ))}
                </div>
            )}
        </div>
    );
}
