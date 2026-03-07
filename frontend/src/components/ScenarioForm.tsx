import { useState } from 'react';
import { useApiQuery } from '../hooks/useApiQuery';
import { listAccounts } from '../api/accounts';
import { listSpendingProfiles } from '../api/spendingProfiles';
import { formatCurrency } from '../utils/format';
import HelpText from './HelpText';
import InfoSection from './InfoSection';
import type { Account } from '../types/account';
import type { Scenario, CreateScenarioRequest, ScenarioAccountInput } from '../types/projection';

const inputStyle = { padding: '0.5rem', border: '1px solid #ccc', borderRadius: '4px', width: '100%' };
const labelStyle = { display: 'block', marginBottom: '0.25rem', fontWeight: 600 as const, fontSize: '0.85rem' };

const STRATEGY_OPTIONS = [
    {
        value: 'fixed_percentage',
        title: 'Fixed Percentage (4% Rule)',
        description: 'Year 1 withdrawal is balance \u00d7 rate. Each subsequent year adjusts for inflation, not recalculated from balance. Predictable income that doesn\'t adapt to market performance.',
    },
    {
        value: 'dynamic_percentage',
        title: 'Dynamic Percentage',
        description: 'Every year, withdraw current balance \u00d7 rate. Income fluctuates with markets. Portfolio cannot mathematically deplete to zero, but income can drop significantly in downturns.',
    },
    {
        value: 'vanguard_dynamic_spending',
        title: 'Vanguard Dynamic Spending',
        description: 'Year 1 is balance \u00d7 rate. Subsequent years recalculate from current balance, but year-over-year change is clamped between a floor and ceiling. Balances adaptability with income stability.',
    },
] as const;

const WITHDRAWAL_ORDER_OPTIONS = [
    {
        value: 'taxable_first',
        title: 'Taxable First',
        description: 'Draw from taxable accounts first, then traditional, then Roth. Preserves tax-advantaged growth longest.',
    },
    {
        value: 'traditional_first',
        title: 'Traditional First',
        description: 'Draw from traditional accounts first. Reduces future RMDs but triggers early tax.',
    },
    {
        value: 'roth_first',
        title: 'Roth First',
        description: 'Draw from Roth first. Unusual but useful for specific tax planning scenarios.',
    },
    {
        value: 'pro_rata',
        title: 'Pro Rata',
        description: 'Withdraw proportionally from all pools based on balance. Smooths tax impact across years.',
    },
] as const;

const ACCOUNT_TYPE_HELP: Record<string, string> = {
    taxable: 'Regular brokerage account. After-tax contributions, growth taxed as capital gains.',
    traditional: 'Pre-tax contributions reduce taxable income now. Withdrawals in retirement taxed as ordinary income.',
    roth: 'After-tax contributions. Growth and qualified withdrawals in retirement are completely tax-free.',
};

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

function mapAccountType(realType: string): string {
    switch (realType) {
        case 'roth': return 'roth';
        case '401k': case 'traditional_ira': return 'traditional';
        default: return 'taxable';
    }
}

export default function ScenarioForm({ initialValues, onSubmit, submitLabel }: ScenarioFormProps) {
    const { data: profiles } = useApiQuery(listSpendingProfiles);
    const { data: accountsPage } = useApiQuery(() => listAccounts(0, 100));
    const existingAccounts: Account[] = accountsPage?.data ?? [];

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
    const [rothConversionStrategy, setRothConversionStrategy] = useState(parsedParams.roth_conversion_strategy ?? 'fixed_amount');
    const [targetBracketRate, setTargetBracketRate] = useState<number>(parsedParams.target_bracket_rate ?? 0.12);
    const [withdrawalOrder, setWithdrawalOrder] = useState(parsedParams.withdrawal_order ?? 'taxable_first');
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

    function linkAccount(index: number, accountId: string) {
        if (!accountId) {
            updateAccount(index, 'linked_account_id', null);
            return;
        }
        const acct = existingAccounts.find(a => a.id === accountId);
        if (acct) {
            setAccounts(prev => prev.map((a, i) => i === index ? {
                ...a,
                linked_account_id: acct.id,
                initial_balance: acct.balance,
                account_type: mapAccountType(acct.type),
            } : a));
        }
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
                filing_status: (rothConversionStrategy === 'fill_bracket' || annualRothConversion > 0) ? filingStatus : null,
                other_income: (rothConversionStrategy === 'fill_bracket' || annualRothConversion > 0) ? otherIncome : null,
                annual_roth_conversion: rothConversionStrategy === 'fixed_amount' && annualRothConversion > 0 ? annualRothConversion : null,
                withdrawal_order: withdrawalOrder !== 'taxable_first' ? withdrawalOrder : null,
                roth_conversion_strategy: rothConversionStrategy !== 'fixed_amount' ? rothConversionStrategy : null,
                target_bracket_rate: rothConversionStrategy === 'fill_bracket' ? targetBracketRate : null,
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
                    <HelpText>Used to calculate your age at each projection year.</HelpText>
                </div>
                <div>
                    <label style={labelStyle}>End Age</label>
                    <input style={inputStyle} type="number" value={endAge} onChange={e => setEndAge(Number(e.target.value))} />
                    <HelpText>Age at which the projection ends. Plan beyond your expected lifespan for safety.</HelpText>
                </div>
                <div>
                    <label style={labelStyle}>Inflation Rate</label>
                    <input style={inputStyle} type="number" step="0.01" value={inflationRate} onChange={e => setInflationRate(Number(e.target.value))} />
                    <HelpText>Annual rate of price increases. 3% is the historical U.S. average.</HelpText>
                </div>
                <div>
                    <label style={labelStyle}>Withdrawal Rate</label>
                    <input style={inputStyle} type="number" step="0.01" value={withdrawalRate} onChange={e => setWithdrawalRate(Number(e.target.value))} />
                    <HelpText>Percentage of portfolio to withdraw annually in retirement.</HelpText>
                </div>
                <div>
                    <label style={labelStyle}>Spending Profile</label>
                    <select style={inputStyle} value={spendingProfileId} onChange={e => setSpendingProfileId(e.target.value)}>
                        <option value="">None</option>
                        {profiles?.map(p => (
                            <option key={p.id} value={p.id}>{p.name}</option>
                        ))}
                    </select>
                    <HelpText>Optional. Defines your expected retirement expenses and non-portfolio income. When linked, the projection shows whether your withdrawals can cover your spending plan.</HelpText>
                </div>
            </div>

            <label style={{ ...labelStyle, marginBottom: '0.5rem' }}>Withdrawal Strategy</label>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                {STRATEGY_OPTIONS.map(opt => (
                    <div
                        key={opt.value}
                        onClick={() => setWithdrawalStrategy(opt.value)}
                        style={{
                            border: `2px solid ${withdrawalStrategy === opt.value ? '#1976d2' : '#e0e0e0'}`,
                            background: withdrawalStrategy === opt.value ? '#e3f2fd' : '#fff',
                            cursor: 'pointer',
                            borderRadius: '8px',
                            padding: '1rem',
                        }}
                    >
                        <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{opt.title}</div>
                        <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>{opt.description}</div>
                    </div>
                ))}
            </div>
            {withdrawalStrategy === 'vanguard_dynamic_spending' && (
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                    <div>
                        <label style={labelStyle}>Ceiling (max increase)</label>
                        <input style={inputStyle} type="number" step="0.01" value={dynamicCeiling} onChange={e => setDynamicCeiling(Number(e.target.value))} />
                        <HelpText>Maximum year-over-year spending increase (e.g., 0.05 = 5%)</HelpText>
                    </div>
                    <div>
                        <label style={labelStyle}>Floor (max decrease)</label>
                        <input style={inputStyle} type="number" step="0.01" value={dynamicFloor} onChange={e => setDynamicFloor(Number(e.target.value))} />
                        <HelpText>Maximum year-over-year spending decrease (e.g., -0.025 = 2.5%)</HelpText>
                    </div>
                </div>
            )}

            <label style={{ ...labelStyle, marginBottom: '0.5rem' }}>Withdrawal Order</label>
            <InfoSection prompt="What is withdrawal order?">
                When you withdraw from your portfolio in retirement, this determines which accounts are drawn from first. Different orders have different tax consequences — for example, drawing from traditional accounts first triggers income tax earlier but preserves Roth growth.
            </InfoSection>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                {WITHDRAWAL_ORDER_OPTIONS.map(opt => (
                    <div
                        key={opt.value}
                        onClick={() => setWithdrawalOrder(opt.value)}
                        style={{
                            border: `2px solid ${withdrawalOrder === opt.value ? '#1976d2' : '#e0e0e0'}`,
                            background: withdrawalOrder === opt.value ? '#e3f2fd' : '#fff',
                            cursor: 'pointer',
                            borderRadius: '8px',
                            padding: '1rem',
                        }}
                    >
                        <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>{opt.title}</div>
                        <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>{opt.description}</div>
                    </div>
                ))}
            </div>

            <h4 style={{ marginBottom: '0.5rem' }}>Roth Conversion</h4>
            <InfoSection prompt="What is Roth conversion?">
                Moving pre-tax retirement funds (Traditional IRA/401k) to a Roth account. You pay income tax on the converted amount now, but all future growth and withdrawals are tax-free. A conversion ladder spreads conversions over multiple years to stay in lower tax brackets.
            </InfoSection>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                <div
                    onClick={() => setRothConversionStrategy('fixed_amount')}
                    style={{
                        border: `2px solid ${rothConversionStrategy === 'fixed_amount' ? '#1976d2' : '#e0e0e0'}`,
                        background: rothConversionStrategy === 'fixed_amount' ? '#e3f2fd' : '#fff',
                        cursor: 'pointer',
                        borderRadius: '8px',
                        padding: '1rem',
                    }}
                >
                    <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>Fixed Amount</div>
                    <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>Convert a fixed dollar amount from traditional to Roth each year. Set to $0 to skip conversions.</div>
                </div>
                <div
                    onClick={() => setRothConversionStrategy('fill_bracket')}
                    style={{
                        border: `2px solid ${rothConversionStrategy === 'fill_bracket' ? '#1976d2' : '#e0e0e0'}`,
                        background: rothConversionStrategy === 'fill_bracket' ? '#e3f2fd' : '#fff',
                        cursor: 'pointer',
                        borderRadius: '8px',
                        padding: '1rem',
                    }}
                >
                    <div style={{ fontWeight: 600, marginBottom: '0.5rem' }}>Fill Tax Bracket</div>
                    <div style={{ fontSize: '0.8rem', color: '#666', lineHeight: 1.4 }}>Automatically convert enough to fill up to a target tax bracket each year. Optimizes conversions to minimize lifetime taxes.</div>
                </div>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '1rem', marginBottom: '1rem' }}>
                {rothConversionStrategy === 'fixed_amount' && (
                    <div>
                        <label style={labelStyle}>Annual Roth Conversion</label>
                        <input style={inputStyle} type="number" value={annualRothConversion} onChange={e => setAnnualRothConversion(Number(e.target.value))} />
                        <HelpText>Fixed dollar amount to convert each year. Set to $0 to skip.</HelpText>
                    </div>
                )}
                {rothConversionStrategy === 'fill_bracket' && (
                    <div>
                        <label style={labelStyle}>Target Tax Bracket</label>
                        <select style={inputStyle} value={targetBracketRate} onChange={e => setTargetBracketRate(Number(e.target.value))}>
                            <option value={0.10}>10%</option>
                            <option value={0.12}>12%</option>
                            <option value={0.22}>22%</option>
                            <option value={0.24}>24%</option>
                            <option value={0.32}>32%</option>
                            <option value={0.35}>35%</option>
                        </select>
                        <HelpText>Convert enough to fill income up to the top of this bracket each year.</HelpText>
                    </div>
                )}
                {(rothConversionStrategy !== 'fixed_amount' || annualRothConversion > 0) && (
                    <>
                        <div>
                            <label style={labelStyle}>Filing Status</label>
                            <select style={inputStyle} value={filingStatus} onChange={e => setFilingStatus(e.target.value)}>
                                <option value="single">Single</option>
                                <option value="married_filing_jointly">Married Filing Jointly</option>
                            </select>
                            <HelpText>Your tax filing status, used to determine the tax bracket for conversion amounts.</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Other Income</label>
                            <input style={inputStyle} type="number" value={otherIncome} onChange={e => setOtherIncome(Number(e.target.value))} />
                            <HelpText>Non-retirement income (salary, rental income) that affects which tax bracket your conversions fall into.</HelpText>
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
            <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '0.75rem' }}>
                Each account represents a pool of investments with its own tax treatment, growth rate, and contribution schedule.
            </div>
            {accounts.map((acct, idx) => (
                <div key={idx} style={{ border: '1px solid #e0e0e0', borderRadius: '8px', padding: '1rem', marginBottom: '0.75rem' }}>
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr auto', gap: '1rem', marginBottom: '0.75rem', alignItems: 'end' }}>
                        <div>
                            <label style={labelStyle}>Link Existing Account</label>
                            <select
                                style={inputStyle}
                                value={acct.linked_account_id ?? ''}
                                onChange={e => linkAccount(idx, e.target.value)}
                            >
                                <option value="">Manual Entry</option>
                                {existingAccounts.map(a => (
                                    <option key={a.id} value={a.id}>
                                        {a.name} ({a.institution ?? a.type}) — {formatCurrency(a.balance)}
                                    </option>
                                ))}
                            </select>
                            <HelpText>
                                {acct.linked_account_id
                                    ? 'Balance updates automatically each time the projection runs.'
                                    : 'Enter values manually, or select an existing account above.'}
                            </HelpText>
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
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', alignItems: 'end' }}>
                        <div>
                            <label style={labelStyle}>Account Type</label>
                            <select style={inputStyle} value={acct.account_type || 'taxable'} onChange={e => updateAccount(idx, 'account_type', e.target.value)}>
                                <option value="taxable">Taxable</option>
                                <option value="traditional">Traditional (Pre-tax)</option>
                                <option value="roth">Roth</option>
                            </select>
                            <HelpText>{ACCOUNT_TYPE_HELP[acct.account_type || 'taxable']}</HelpText>
                        </div>
                        <div>
                            <label style={labelStyle}>Initial Balance{acct.linked_account_id ? ' (live)' : ''}</label>
                            <input
                                style={{ ...inputStyle, ...(acct.linked_account_id ? { background: '#f5f5f5' } : {}) }}
                                type="number"
                                value={acct.initial_balance}
                                onChange={e => updateAccount(idx, 'initial_balance', Number(e.target.value))}
                                readOnly={!!acct.linked_account_id}
                            />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Contribution</label>
                            <input style={inputStyle} type="number" value={acct.annual_contribution} onChange={e => updateAccount(idx, 'annual_contribution', Number(e.target.value))} />
                        </div>
                        <div>
                            <label style={labelStyle}>Expected Return</label>
                            <input style={inputStyle} type="number" step="0.01" value={acct.expected_return} onChange={e => updateAccount(idx, 'expected_return', Number(e.target.value))} />
                        </div>
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
