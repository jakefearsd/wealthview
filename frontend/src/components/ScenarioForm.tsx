import { useState } from 'react';
import { useApiQuery } from '../hooks/useApiQuery';
import { listSpendingProfiles } from '../api/spendingProfiles';
import type { Scenario, CreateScenarioRequest, ScenarioAccountInput } from '../types/projection';

const inputStyle = { padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '100%' };
const labelStyle = { display: 'block', marginBottom: '0.25rem', fontWeight: 600 as const, fontSize: '0.85rem' };

interface ScenarioFormProps {
    initialValues?: Scenario | null;
    onSubmit: (data: CreateScenarioRequest) => Promise<void>;
    submitLabel: string;
}

function defaultAccount(): ScenarioAccountInput {
    return {
        linked_account_id: null,
        initial_balance: 100000,
        annual_contribution: 10000,
        expected_return: 0.07,
        account_type: 'taxable',
    };
}

export default function ScenarioForm({ initialValues, onSubmit, submitLabel }: ScenarioFormProps) {
    const { data: profiles } = useApiQuery(listSpendingProfiles);

    const parsedParams = initialValues?.params_json ? JSON.parse(initialValues.params_json) : {};

    const [name, setName] = useState(initialValues?.name ?? '');
    const [retirementDate, setRetirementDate] = useState(initialValues?.retirement_date ?? '');
    const [endAge, setEndAge] = useState(initialValues?.end_age ?? 90);
    const [inflationRate, setInflationRate] = useState(initialValues?.inflation_rate ?? 0.03);
    const [birthYear, setBirthYear] = useState<number>(parsedParams.birth_year ?? 1990);
    const [withdrawalRate, setWithdrawalRate] = useState<number>(parsedParams.withdrawal_rate ?? 0.04);
    const [withdrawalStrategy, setWithdrawalStrategy] = useState(parsedParams.withdrawal_strategy ?? 'fixed_percentage');
    const [dynamicCeiling, setDynamicCeiling] = useState<number>(parsedParams.dynamic_ceiling ?? 0.05);
    const [dynamicFloor, setDynamicFloor] = useState<number>(parsedParams.dynamic_floor ?? -0.025);
    const [filingStatus, setFilingStatus] = useState(parsedParams.filing_status ?? 'single');
    const [otherIncome, setOtherIncome] = useState<number>(parsedParams.other_income ?? 0);
    const [annualRothConversion, setAnnualRothConversion] = useState<number>(parsedParams.annual_roth_conversion ?? 0);
    const [spendingProfileId, setSpendingProfileId] = useState<string>(initialValues?.spending_profile?.id ?? '');
    const [accounts, setAccounts] = useState<ScenarioAccountInput[]>(
        initialValues?.accounts?.map(a => ({
            linked_account_id: a.linked_account_id,
            initial_balance: a.initial_balance,
            annual_contribution: a.annual_contribution,
            expected_return: a.expected_return,
            account_type: a.account_type || 'taxable',
        })) ?? [defaultAccount()]
    );
    const [saving, setSaving] = useState(false);

    function updateAccount(index: number, field: keyof ScenarioAccountInput, value: string | number | null) {
        setAccounts(prev => prev.map((a, i) => i === index ? { ...a, [field]: value } : a));
    }

    function addAccount() {
        setAccounts(prev => [...prev, defaultAccount()]);
    }

    function removeAccount(index: number) {
        setAccounts(prev => prev.filter((_, i) => i !== index));
    }

    async function handleSubmit() {
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
                spending_profile_id: spendingProfileId || null,
                accounts,
            };
            await onSubmit(request);
        } finally {
            setSaving(false);
        }
    }

    return (
        <div>
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
                <div>
                    <label style={labelStyle}>Spending Profile</label>
                    <select style={inputStyle} value={spendingProfileId} onChange={e => setSpendingProfileId(e.target.value)}>
                        <option value="">None</option>
                        {profiles?.map(p => (
                            <option key={p.id} value={p.id}>{p.name}</option>
                        ))}
                    </select>
                </div>
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

            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <h4>Accounts</h4>
                <button
                    onClick={addAccount}
                    style={{ padding: '0.25rem 0.75rem', background: '#4caf50', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                >
                    + Add Account
                </button>
            </div>
            {accounts.map((acct, idx) => (
                <div key={idx} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr auto', gap: '1rem', marginBottom: '0.75rem', alignItems: 'end' }}>
                    <div>
                        <label style={labelStyle}>Account Type</label>
                        <select style={inputStyle} value={acct.account_type || 'taxable'} onChange={e => updateAccount(idx, 'account_type', e.target.value)}>
                            <option value="taxable">Taxable</option>
                            <option value="traditional">Traditional (Pre-tax)</option>
                            <option value="roth">Roth</option>
                        </select>
                    </div>
                    <div>
                        <label style={labelStyle}>Initial Balance</label>
                        <input style={inputStyle} type="number" value={acct.initial_balance} onChange={e => updateAccount(idx, 'initial_balance', Number(e.target.value))} />
                    </div>
                    <div>
                        <label style={labelStyle}>Annual Contribution</label>
                        <input style={inputStyle} type="number" value={acct.annual_contribution} onChange={e => updateAccount(idx, 'annual_contribution', Number(e.target.value))} />
                    </div>
                    <div>
                        <label style={labelStyle}>Expected Return</label>
                        <input style={inputStyle} type="number" step="0.01" value={acct.expected_return} onChange={e => updateAccount(idx, 'expected_return', Number(e.target.value))} />
                    </div>
                    <div>
                        {accounts.length > 1 && (
                            <button
                                onClick={() => removeAccount(idx)}
                                style={{ padding: '0.5rem', background: 'none', border: '1px solid #d32f2f', color: '#d32f2f', borderRadius: '4px', cursor: 'pointer', fontSize: '0.85rem' }}
                            >
                                Remove
                            </button>
                        )}
                    </div>
                </div>
            ))}

            <button
                onClick={handleSubmit}
                disabled={saving}
                style={{ padding: '0.5rem 1rem', background: '#1976d2', color: '#fff', border: 'none', borderRadius: '4px', cursor: 'pointer', marginTop: '0.5rem' }}
            >
                {saving ? 'Saving...' : submitLabel}
            </button>
        </div>
    );
}
