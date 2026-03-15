import { useState } from 'react';
import { useApiQuery } from '../hooks/useApiQuery';
import { listAccounts } from '../api/accounts';
import { listSpendingProfiles } from '../api/spendingProfiles';
import { listIncomeSources } from '../api/incomeSources';
import { formatCurrency, formatCurrencyInput, parseCurrencyInput } from '../utils/format';
import HelpText from './HelpText';
import WithdrawalStrategySection from './WithdrawalStrategySection';
import RothConversionSection from './RothConversionSection';
import type { Account } from '../types/account';
import type { Scenario, CreateScenarioRequest, ScenarioAccountInput, ScenarioIncomeSourceInput } from '../types/projection';
import { inputStyle, labelStyle } from '../utils/styles';

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
        expected_return: 7,
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
    const { data: availableIncomeSources } = useApiQuery(listIncomeSources);
    const existingAccounts: Account[] = accountsPage?.data ?? [];

    const parsedParams = initialValues?.params_json ? JSON.parse(initialValues.params_json) : {};

    const [name, setName] = useState(initialValues?.name ?? '');
    const [retirementDate, setRetirementDate] = useState(initialValues?.retirement_date ?? '');
    const [endAge, setEndAge] = useState(initialValues?.end_age ?? 90);
    const [inflationRate, setInflationRate] = useState((initialValues?.inflation_rate ?? 0.03) * 100);
    const [birthYear, setBirthYear] = useState<number>(parsedParams.birth_year ?? 1990);
    const [withdrawalRate, setWithdrawalRate] = useState<number>((parsedParams.withdrawal_rate ?? 0.04) * 100);
    const [withdrawalStrategy, setWithdrawalStrategy] = useState(parsedParams.withdrawal_strategy ?? 'fixed_percentage');
    const [dynamicCeiling, setDynamicCeiling] = useState<number>((parsedParams.dynamic_ceiling ?? 0.05) * 100);
    const [dynamicFloor, setDynamicFloor] = useState<number>((parsedParams.dynamic_floor ?? -0.025) * 100);
    const [filingStatus, setFilingStatus] = useState(parsedParams.filing_status ?? 'single');
    const [otherIncome, setOtherIncome] = useState<number>(parsedParams.other_income ?? 0);
    const [annualRothConversion, setAnnualRothConversion] = useState<number>(parsedParams.annual_roth_conversion ?? 0);
    const [rothConversionStrategy, setRothConversionStrategy] = useState(parsedParams.roth_conversion_strategy ?? 'fixed_amount');
    const [targetBracketRate, setTargetBracketRate] = useState<number>((parsedParams.target_bracket_rate ?? 0.12) * 100);
    const [rothConversionStartYear, setRothConversionStartYear] = useState<number | null>(parsedParams.roth_conversion_start_year ?? null);
    const [withdrawalOrder, setWithdrawalOrder] = useState(parsedParams.withdrawal_order ?? 'taxable_first');
    const deriveSpendingPlan = () =>
        initialValues?.guardrail_profile?.active ? 'guardrail' : (initialValues?.spending_profile?.id ?? '');
    const [spendingPlanSelection, setSpendingPlanSelection] = useState<string>(deriveSpendingPlan);
    const [accounts, setAccounts] = useState<ScenarioAccountInput[]>(
        initialValues?.accounts?.map(a => ({
            linked_account_id: a.linked_account_id,
            initial_balance: a.initial_balance,
            annual_contribution: a.annual_contribution,
            expected_return: a.expected_return * 100,
            account_type: a.account_type || 'taxable',
        })) ?? [defaultAccount()]
    );
    const [selectedIncomeSources, setSelectedIncomeSources] = useState<ScenarioIncomeSourceInput[]>(
        initialValues?.income_sources?.map(is => ({
            income_source_id: is.income_source_id,
            override_annual_amount: is.override_annual_amount,
        })) ?? []
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
                inflation_rate: inflationRate / 100,
                birth_year: birthYear,
                withdrawal_rate: withdrawalRate / 100,
                withdrawal_strategy: withdrawalStrategy,
                dynamic_ceiling: withdrawalStrategy === 'vanguard_dynamic_spending' ? dynamicCeiling / 100 : null,
                dynamic_floor: withdrawalStrategy === 'vanguard_dynamic_spending' ? dynamicFloor / 100 : null,
                filing_status: (rothConversionStrategy === 'fill_bracket' || annualRothConversion > 0) ? filingStatus : null,
                other_income: (rothConversionStrategy === 'fill_bracket' || annualRothConversion > 0) ? otherIncome : null,
                annual_roth_conversion: rothConversionStrategy === 'fixed_amount' && annualRothConversion > 0 ? annualRothConversion : null,
                withdrawal_order: withdrawalOrder !== 'taxable_first' ? withdrawalOrder : null,
                roth_conversion_strategy: rothConversionStrategy !== 'fixed_amount' ? rothConversionStrategy : null,
                target_bracket_rate: rothConversionStrategy === 'fill_bracket' ? targetBracketRate / 100 : null,
                roth_conversion_start_year: rothConversionStartYear || null,
                spending_profile_id: (spendingPlanSelection && spendingPlanSelection !== 'guardrail') ? spendingPlanSelection : null,
                use_guardrail_profile: spendingPlanSelection === 'guardrail' ? true : null,
                accounts: accounts.map(a => ({ ...a, expected_return: a.expected_return / 100 })),
                income_sources: selectedIncomeSources,
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
                    <label style={labelStyle}>Inflation Rate (%)</label>
                    <input style={inputStyle} type="number" step="0.1" value={inflationRate} onChange={e => setInflationRate(Number(e.target.value))} />
                    <HelpText>Annual rate of price increases. 3 = 3%, the historical U.S. average.</HelpText>
                </div>
                <div>
                    <label style={labelStyle}>Withdrawal Rate (%)</label>
                    <input style={inputStyle} type="number" step="0.1" value={withdrawalRate} onChange={e => setWithdrawalRate(Number(e.target.value))} />
                    <HelpText>Percentage of portfolio to withdraw annually in retirement. 4 = 4%.</HelpText>
                </div>
                <div>
                    <label style={labelStyle}>Spending Plan</label>
                    <select style={inputStyle} value={spendingPlanSelection} onChange={e => setSpendingPlanSelection(e.target.value)}>
                        <option value="">None (use withdrawal rate)</option>
                        {profiles?.map(p => (
                            <option key={p.id} value={p.id}>{p.name}</option>
                        ))}
                        {initialValues?.guardrail_profile && (
                            <option value="guardrail">
                                &#9881; {initialValues.guardrail_profile.name}{initialValues.guardrail_profile.stale ? ' (stale)' : ''}{!initialValues.guardrail_profile.active ? ' (inactive)' : ''}
                            </option>
                        )}
                    </select>
                    {spendingPlanSelection === 'guardrail' && !initialValues?.guardrail_profile && (
                        <div style={{ fontSize: '0.8rem', color: '#e65100', marginTop: '0.25rem' }}>
                            Guardrail profile no longer available. Please select another spending plan.
                        </div>
                    )}
                    <HelpText>Choose a spending profile (user-defined tiers) or guardrail profile (Monte Carlo optimized). When linked, the projection withdraws what you need each year, minus non-portfolio income.</HelpText>
                </div>
            </div>

            {availableIncomeSources && availableIncomeSources.length > 0 && (
                <div style={{ marginBottom: '1rem' }}>
                    <h4 style={{ marginBottom: '0.5rem' }}>Income Sources</h4>
                    <div style={{ fontSize: '0.85rem', color: '#666', marginBottom: '0.75rem' }}>
                        Select income sources (Social Security, pensions, rental income, etc.) to include in this projection. You can optionally override the annual amount per scenario.
                    </div>
                    {availableIncomeSources.map(is => {
                        const selected = selectedIncomeSources.find(s => s.income_source_id === is.id);
                        return (
                            <div key={is.id} style={{ border: '1px solid #e0e0e0', borderRadius: '8px', padding: '0.75rem', marginBottom: '0.5rem' }}>
                                <div style={{ display: 'flex', alignItems: 'center', gap: '0.75rem' }}>
                                    <input
                                        type="checkbox"
                                        checked={!!selected}
                                        onChange={e => {
                                            if (e.target.checked) {
                                                setSelectedIncomeSources(prev => [...prev, { income_source_id: is.id, override_annual_amount: null }]);
                                            } else {
                                                setSelectedIncomeSources(prev => prev.filter(s => s.income_source_id !== is.id));
                                            }
                                        }}
                                    />
                                    <div style={{ flex: 1 }}>
                                        <strong>{is.name}</strong>
                                        <span style={{ color: '#666', marginLeft: '0.5rem', fontSize: '0.85rem' }}>
                                            ({is.income_type.replace(/_/g, ' ')}) — {formatCurrency(is.annual_amount)}/yr
                                        </span>
                                    </div>
                                    {selected && (
                                        <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem' }}>
                                            <label style={{ fontSize: '0.85rem', color: '#666' }}>Override:</label>
                                            <input
                                                style={{ ...inputStyle, width: '140px' }}
                                                type="text"
                                                inputMode="decimal"
                                                placeholder="Use default"
                                                value={selected.override_annual_amount != null ? formatCurrencyInput(selected.override_annual_amount) : ''}
                                                onChange={e => {
                                                    const val = e.target.value ? Number(parseCurrencyInput(e.target.value)) || null : null;
                                                    setSelectedIncomeSources(prev => prev.map(s =>
                                                        s.income_source_id === is.id ? { ...s, override_annual_amount: val } : s
                                                    ));
                                                }}
                                            />
                                        </div>
                                    )}
                                </div>
                            </div>
                        );
                    })}
                </div>
            )}

            <WithdrawalStrategySection
                withdrawalStrategy={withdrawalStrategy}
                onWithdrawalStrategyChange={setWithdrawalStrategy}
                dynamicCeiling={dynamicCeiling}
                onDynamicCeilingChange={setDynamicCeiling}
                dynamicFloor={dynamicFloor}
                onDynamicFloorChange={setDynamicFloor}
                withdrawalOrder={withdrawalOrder}
                onWithdrawalOrderChange={setWithdrawalOrder}
            />

            <RothConversionSection
                rothConversionStrategy={rothConversionStrategy}
                onRothConversionStrategyChange={setRothConversionStrategy}
                annualRothConversion={annualRothConversion}
                onAnnualRothConversionChange={setAnnualRothConversion}
                targetBracketRate={targetBracketRate}
                onTargetBracketRateChange={setTargetBracketRate}
                rothConversionStartYear={rothConversionStartYear}
                onRothConversionStartYearChange={setRothConversionStartYear}
                filingStatus={filingStatus}
                onFilingStatusChange={setFilingStatus}
                otherIncome={otherIncome}
                onOtherIncomeChange={setOtherIncome}
            />

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
                    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: '1rem', alignItems: 'start' }}>
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
                                type="text"
                                inputMode="decimal"
                                value={formatCurrencyInput(acct.initial_balance)}
                                onChange={e => updateAccount(idx, 'initial_balance', Number(parseCurrencyInput(e.target.value)) || 0)}
                                readOnly={!!acct.linked_account_id}
                            />
                        </div>
                        <div>
                            <label style={labelStyle}>Annual Contribution</label>
                            <input style={inputStyle} type="text" inputMode="decimal" value={formatCurrencyInput(acct.annual_contribution)} onChange={e => updateAccount(idx, 'annual_contribution', Number(parseCurrencyInput(e.target.value)) || 0)} />
                        </div>
                        <div>
                            <label style={labelStyle}>Expected Return (%)</label>
                            <input style={inputStyle} type="number" step="0.1" value={acct.expected_return} onChange={e => updateAccount(idx, 'expected_return', Number(e.target.value))} />
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
